/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019 Katrix
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ackcord.requests

import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import ackcord.AckCord
import ackcord.util.AckCordRequestSettings
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, MergePreferred, Partition, Sink, Source}
import akka.stream.{FlowShape, OverflowStrategy}
import akka.util.{ByteString, Timeout}
import akka.{Done, NotUsed}

object RequestStreams {

  private def findCustomHeader[H <: ModeledCustomHeader[H]](
      companion: ModeledCustomHeaderCompanion[H],
      response: HttpResponse
  ): Option[H] =
    response.headers.collectFirst {
      case h if h.name == companion.name => companion.parse(h.value).toOption
    }.flatten

  private def remainingRequests(response: HttpResponse): Int =
    findCustomHeader(`X-RateLimit-Remaining`, response).fold(-1)(_.remaining)

  private def requestsForUri(response: HttpResponse): Int =
    findCustomHeader(`X-RateLimit-Limit`, response).fold(-1)(_.limit)

  private def timeTilReset(response: HttpResponse): FiniteDuration =
    findCustomHeader(`Retry-After`, response)
      .map(_.tilReset)
      .orElse {
        findCustomHeader(`X-RateLimit-Reset`, response).map { header =>
          Instant.now().until(header.resetAt, ChronoUnit.MILLIS).millis
        }
      }
      .getOrElse(-1.millis)

  private def isGlobalRatelimit(response: HttpResponse): Boolean =
    findCustomHeader(`X-Ratelimit-Global`, response).fold(false)(_.isGlobal)

  private val userAgent = `User-Agent`(s"DiscordBot (https://github.com/Katrix/AckCord, ${AckCord.Version})")

  /**
    * A basic request flow which will send requests to Discord, and
    * receive responses. This flow does not account for ratelimits. Only
    * use it if you know what you're doing.
    * @param credentials The credentials to use when sending the requests.
    */
  def requestFlowWithoutRatelimit[Data, Ctx](
      credentials: HttpCredentials,
      parallelism: Int = 4,
      rateLimitActor: ActorRef
  )(implicit system: ActorSystem): Flow[Request[Data, Ctx], RequestAnswer[Data, Ctx], NotUsed] =
    createHttpRequestFlow[Data, Ctx](credentials)
      .via(requestHttpFlow)
      .via(requestParser(parallelism))
      .alsoTo(sendRatelimitUpdates(rateLimitActor))

  /**
    * A request flow which will send requests to Discord, and receive responses.
    * If it encounters a ratelimit it will backpressure.
    * @param credentials The credentials to use when sending the requests.
    * @param bufferSize The size of the internal buffer used before messages
    *                   are sent.
    * @param overflowStrategy The strategy to use if the buffer overflows.
    * @param maxAllowedWait The maximum allowed wait time for the route
    *                       specific ratelimits.
    * @param parallelism The amount of requests to run at the same time.
    * @param rateLimitActor An actor responsible for keeping track of ratelimits.
    */
  def requestFlow[Data, Ctx](
      credentials: HttpCredentials,
      bufferSize: Int = 100,
      overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure,
      maxAllowedWait: FiniteDuration = 2.minutes,
      parallelism: Int = 4,
      rateLimitActor: ActorRef
  )(implicit system: ActorSystem): Flow[Request[Data, Ctx], RequestAnswer[Data, Ctx], NotUsed] = {

    val graph = GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val in         = builder.add(Flow[Request[Data, Ctx]])
      val buffer     = builder.add(Flow[Request[Data, Ctx]].buffer(bufferSize, overflowStrategy))
      val ratelimits = builder.add(ratelimitFlow[Data, Ctx](rateLimitActor, maxAllowedWait, parallelism))

      val partition = builder.add(Partition[MaybeRequest[Data, Ctx]](2, {
        case _: RequestDropped[_] => 1
        case _: Request[_, _]     => 0
      }))
      val requests = partition.out(0).collect { case request: Request[Data, Ctx]  => request }
      val dropped  = partition.out(1).collect { case dropped: RequestDropped[Ctx] => dropped }

      val network = builder.add(requestFlowWithoutRatelimit[Data, Ctx](credentials, parallelism, rateLimitActor))
      val out     = builder.add(Merge[RequestAnswer[Data, Ctx]](2))

      // format: OFF
      in ~> buffer ~> ratelimits ~> partition
                                    requests ~> network ~> out
                                    dropped  ~>            out
      // format: ON

      FlowShape(in.in, out.out)
    }

    Flow.fromGraph(graph)
  }

  /**
    * A request flow which obeys route specific rate limits, but not global ones.
    * @param ratelimiter An actor responsible for keeping track of ratelimits.
    * @param maxAllowedWait The maximum allowed wait time for the route
    *                       specific ratelimits.
    * @param parallelism The amount of requests to run at the same time.
    */
  def ratelimitFlow[Data, Ctx](ratelimiter: ActorRef, maxAllowedWait: FiniteDuration, parallelism: Int = 4)(
      implicit system: ActorSystem
  ): Flow[Request[Data, Ctx], MaybeRequest[Data, Ctx], NotUsed] = {
    implicit val triggerTimeout: Timeout = Timeout(maxAllowedWait)
    Flow[Request[Data, Ctx]].mapAsyncUnordered(parallelism) { request =>
      //We don't use ask here to get be able to create a RequestDropped instance
      import system.dispatcher
      val future = ratelimiter ? Ratelimiter.WantToPass(request.route.rawRoute, request)

      future.mapTo[Request[Data, Ctx]].recover {
        case _: AskTimeoutException => RequestDropped(request.context, request.route.uri, request.route.rawRoute)
      }
    }
  }.named("Ratelimiter")

  private def createHttpRequestFlow[Data, Ctx](
      credentials: HttpCredentials
  )(implicit system: ActorSystem): Flow[Request[Data, Ctx], (HttpRequest, Request[Data, Ctx]), NotUsed] = {
    val baseFlow = Flow[Request[Data, Ctx]]

    val withLogging =
      if (AckCordRequestSettings().LogSentREST)
        baseFlow.log(
          "Sent REST request", { request =>
            val loggingBody = request.bodyForLogging.fold("")(body => s" and content $body")
            s"to ${request.route.uri} with method ${request.route.method}$loggingBody"
          }
        )
      else baseFlow

    withLogging
      .map { request =>
        val route = request.route
        val auth  = Authorization(credentials)
        val httpRequest = HttpRequest(
          route.method,
          route.uri,
          immutable.Seq(auth, userAgent) ++ request.extraHeaders,
          request.requestBody
        )

        httpRequest -> request
      }
  }.named("CreateRequest")

  private def requestHttpFlow[Data, Ctx](
      implicit system: ActorSystem
  ): Flow[(HttpRequest, Request[Data, Ctx]), (Try[HttpResponse], Request[Data, Ctx]), NotUsed] =
    Http().superPool[Request[Data, Ctx]]()

  private def requestParser[Data, Ctx](
      breadth: Int
  )(implicit system: ActorSystem): Flow[(Try[HttpResponse], Request[Data, Ctx]), RequestAnswer[Data, Ctx], NotUsed] = {
    MapWithMaterializer
      .flow[(Try[HttpResponse], Request[Data, Ctx]), Source[RequestAnswer[Data, Ctx], NotUsed]] { implicit mat =>
        {
          case (response, request) =>
            val route    = request.route
            val uri      = route.uri
            val rawRoute = route.rawRoute
            response match {
              case Success(httpResponse) =>
                val tilReset     = timeTilReset(httpResponse)
                val tilRatelimit = remainingRequests(httpResponse)
                val requestLimit = requestsForUri(httpResponse)

                httpResponse.status match {
                  case StatusCodes.TooManyRequests =>
                    httpResponse.discardEntityBytes()
                    Source.single(
                      RequestRatelimited(
                        request.context,
                        isGlobalRatelimit(httpResponse),
                        tilReset,
                        requestLimit,
                        uri,
                        rawRoute
                      )
                    )
                  case e if e.isFailure() =>
                    httpResponse.entity.dataBytes
                      .fold(ByteString.empty)(_ ++ _)
                      .flatMapConcat { eBytes =>
                        Source.single(
                          RequestError(request.context, new HttpException(e, Some(eBytes.utf8String)), uri, rawRoute)
                        )
                      }
                      .mapMaterializedValue(_ => NotUsed)
                  case StatusCodes.NoContent =>
                    httpResponse.discardEntityBytes()
                    Source
                      .single(HttpEntity.Empty)
                      .via(request.parseResponse(breadth))
                      .recover {
                        case Unmarshaller.NoContentException =>
                          throw new HttpException(
                            StatusCodes.NoContent,
                            Some(s"Encountered NoContentException when trying to parse an ${request.getClass}")
                          )
                      }
                      .map { data =>
                        RequestResponse(data, request.context, tilRatelimit, tilReset, requestLimit, uri, rawRoute)
                      }
                  case _ => //Should be success
                    Source
                      .single(httpResponse.entity)
                      .via(request.parseResponse(breadth))
                      .map { data =>
                        RequestResponse(data, request.context, tilRatelimit, tilReset, requestLimit, uri, rawRoute)
                      }
                }

              case Failure(e) =>
                Source.single(RequestError(request.context, e, uri, rawRoute))
            }
        }
      }
      .flatMapMerge(breadth, identity)
  }.named("RequestParser")

  private def sendRatelimitUpdates[Data, Ctx](rateLimitActor: ActorRef): Sink[RequestAnswer[Data, Ctx], Future[Done]] =
    Sink
      .foreach[RequestAnswer[Data, Ctx]] { answer =>
        val isGlobal = answer match {
          case ratelimited: RequestRatelimited[Ctx] => ratelimited.global
          case _                                    => false
        }

        val tilReset          = answer.tilReset
        val remainingRequests = answer.remainingRequests
        val requestLimit      = answer.uriRequestLimit
        val rawRoute          = answer.rawRoute
        if (tilReset > 0.millis && remainingRequests != -1 && requestLimit != -1) {
          rateLimitActor ! Ratelimiter.UpdateRatelimits(rawRoute, isGlobal, tilReset, remainingRequests, requestLimit)
        }
      }
      .async
      .named("SendAnswersToRatelimiter")

  /**
    * A flow that only returns successful responses.
    */
  def dataResponses[Data, Ctx]: Flow[RequestAnswer[Data, Ctx], (Data, Ctx), NotUsed] =
    Flow[RequestAnswer[Data, Ctx]].collect {
      case response: RequestResponse[Data, Ctx] => response.data -> response.context
    }

  /**
    * A request flow that will retry failed requests.
    * @param credentials The credentials to use when sending the requests.
    * @param bufferSize The size of the internal buffer used before messages
    *                   are sent.
    * @param overflowStrategy The strategy to use if the buffer overflows.
    * @param maxAllowedWait The maximum allowed wait time for the route
    *                       specific ratelimits.
    * @param parallelism The amount of requests to run at the same time.
    * @param rateLimitActor An actor responsible for keeping track of ratelimits.
    */
  def retryRequestFlow[Data, Ctx](
      credentials: HttpCredentials,
      bufferSize: Int = 100,
      overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure,
      maxAllowedWait: FiniteDuration = 2.minutes,
      parallelism: Int = 4,
      maxRetryCount: Int = 3,
      rateLimitActor: ActorRef
  )(implicit system: ActorSystem): Flow[Request[Data, Ctx], RequestResponse[Data, Ctx], NotUsed] = {
    val graph = GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val addContextStart =
        builder.add(Flow[Request[Data, Ctx]].map(request => request.withContext((0, request))))
      def requestFlow =
        RequestStreams
          .requestFlow[Data, (Int, Request[Data, Ctx])](
            credentials,
            bufferSize,
            overflowStrategy,
            maxAllowedWait,
            parallelism,
            rateLimitActor
          )
      val allRequests = builder.add(MergePreferred[RequestAnswer[Data, (Int, Request[Data, Ctx])]](1))

      val partitioner = builder.add(
        Partition[RequestAnswer[Data, (Int, Request[Data, Ctx])]](2, {
          case _: RequestResponse[_, _] => 0
          case _: FailedRequest[_]      => 1
        })
      )

      val successful = partitioner.out(0)
      val unwrapSuccess = builder.add(
        Flow[RequestAnswer[Data, (Int, Request[Data, Ctx])]]
          .collect {
            case response: RequestResponse[Data, (Int, Request[Data, Ctx])] =>
              response.withContext(response.context._2.context)
          }
      )

      //Ratelimiter should take care of the ratelimits through back-pressure
      val failed = partitioner.out(1)

      val processFailed = builder.add(
        Flow[RequestAnswer[Data, (Int, Request[Data, Ctx])]]
          .collect {
            case failed: FailedRequest[(Int, Request[Data, Ctx])] if failed.context._1 + 1 < maxRetryCount =>
              val request = failed.context._2
              val trips   = failed.context._1
              request.withContext((trips + 1, request))
          }
      )

      // format: OFF

      addContextStart       ~> requestFlow ~> allRequests.in(0);allRequests ~> partitioner
      allRequests.preferred <~ requestFlow <~ processFailed                 <~ failed.outlet
                                                                               successful    ~> unwrapSuccess

      // format: ON

      FlowShape(addContextStart.in, unwrapSuccess.out)
    }

    Flow.fromGraph(graph)
  }

  def addOrdering[A, B](inner: Flow[A, B, NotUsed]): Flow[A, B, NotUsed] = Flow[A].flatMapConcat { a =>
    Source.single(a).via(inner)
  }
}
