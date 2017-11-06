/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2017 Katrix
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
package net.katsstuff.ackcord.http.websocket.voice

import scala.concurrent.duration._
import scala.util.control.NonFatal

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, SourceQueueWithComplete}
import io.circe
import io.circe.parser
import net.katsstuff.ackcord.data.{RawSnowflake, UserId}
import net.katsstuff.ackcord.http.websocket.AbstractWsHandler
import net.katsstuff.ackcord.http.websocket.AbstractWsHandler.Data
import net.katsstuff.ackcord.http.websocket.voice.VoiceUDPHandler.{Disconnect, DoIPDiscovery, FoundIP, StartConnection}
import net.katsstuff.ackcord.util.AckCordSettings
import net.katsstuff.ackcord.{AckCord, AudioAPIMessage}

/**
  * Responsible for handling the websocket connection part of voice data.
  * @param address The address to connect to, not including the websocket protocol.
  * @param serverId The serverId
  * @param userId The client userId
  * @param sessionId The session id received in [[net.katsstuff.ackcord.APIMessage.VoiceStateUpdate]]
  * @param token The token received in [[net.katsstuff.ackcord.APIMessage.VoiceServerUpdate]]
  * @param sendTo The actor to send all [[AudioAPIMessage]]s to unless noted otherwise
  * @param sendSoundTo The actor to send [[AudioAPIMessage.ReceivedData]] to.
  * @param mat The [[Materializer]] to use
  */
class VoiceWsHandler(
    address: String,
    serverId: RawSnowflake,
    userId: UserId,
    sessionId: String,
    token: String,
    sendTo: Option[ActorRef],
    sendSoundTo: Option[ActorRef]
)(implicit mat: Materializer)
    extends AbstractWsHandler[VoiceMessage, ResumeData] {
  import AbstractWsHandler._
  import VoiceWsHandler._
  import VoiceWsProtocol._

  private implicit val system: ActorSystem = context.system

  def parseMessage: Flow[Message, Either[circe.Error, VoiceMessage[_]], NotUsed] = {
    val jsonFlow = Flow[Message]
      .collect {
        case t: TextMessage => t.textStream.fold("")(_ + _)
      }
      .flatMapConcat(identity)

    val withLogging = if (AckCordSettings().LogReceivedWs) {
      jsonFlow.log("Received payload")
    } else jsonFlow

    withLogging.map(parser.parse(_).flatMap(_.as[VoiceMessage[_]]))
  }

  override def wsUri: Uri = Uri(s"wss://$address").withQuery(Query("v" -> AckCord.DiscordVoiceVersion))

  onTransition {
    case Inactive -> Active => self ! SendIdentify //We act first here
  }

  override def whenInactive: StateFunction = whenInactiveBase.orElse {
    case Event(ConnectionDied, _) => stay()
  }

  when(Active) {
    case Event(InitSink, _) =>
      sender() ! AckSink
      stay()
    case Event(CompletedSink, _) =>
      log.info("Websocket connection completed")
      self ! Logout
      stay()
    case Event(Status.Failure(e), _) =>
      log.error(e, "Connection interrupted")
      throw e
    case Event(Left(NonFatal(e)), _) => throw e
    case event @ Event(Right(_: VoiceMessage[_]), _) =>
      val res = handleWsMessages(event)
      sender() ! AckSink
      res
    case Event(SendIdentify, WithQueue(queue, _)) =>
      val identifyObject = IdentifyData(serverId, userId, sessionId, token)

      val payload = createPayload(Identify(identifyObject))
      queue.offer(TextMessage(payload))

      stay()
    case Event(SendSelectProtocol, WithUDPActor(Some(IPData(localAddress, port)), _, _, _, _, queue, _)) =>
      val protocolObj = SelectProtocolData("udp", SelectProtocolConnectionData(localAddress, port, "xsalsa20_poly1305"))
      val payload     = createPayload(SelectProtocol(protocolObj))
      queue.offer(TextMessage(payload))
      stay()
    case Event(SendHeartbeat, data @ WithUDPActor(_, receivedAck, _, _, _, queue, _)) =>
      if (receivedAck) {
        val nonce = System.currentTimeMillis().toInt

        val payload = createPayload(Heartbeat(nonce))
        queue.offer(TextMessage(payload))
        log.debug("Sent Heartbeat")

        stay using data.copy(receivedAck = false, previousNonce = Some(nonce))
      } else throw new AckException("Did not receive a Heartbeat ACK between heartbeats")
    case Event(SendHeartbeat, data @ WithHeartbeat(_, receivedAck, _, queue, _)) =>
      if (receivedAck) {
        val nonce = System.currentTimeMillis().toInt

        val payload = createPayload(Heartbeat(nonce))
        queue.offer(TextMessage(payload))
        log.debug("Sent Heartbeat")

        stay using data.copy(receivedAck = false, previousNonce = Some(nonce))
      } else throw new AckException("Did not receive a Heartbeat ACK between heartbeats")
    case Event(FoundIP(localAddress, port), data: WithUDPActor) =>
      self ! SendSelectProtocol
      stay using data.copy(ipData = Some(IPData(localAddress, port)))
    case Event(SetSpeaking(speaking), data: WithUDPActor) =>
      val message = SpeakingData(speaking, None, data.ssrc, Some(userId))
      val payload = createPayload(Speaking(message))

      data.queue.offer(TextMessage(payload))
      stay()
    case Event(ConnectionDied, _) =>
      throw new IllegalStateException("Voice connection died") //This should never happen as long as we're in the active state
    case Event(Logout, data: WithUDPActor) =>
      data.queueOpt.foreach(_.complete())
      data.connectionActor ! Disconnect
      log.info("Logging out, Stopping")
      stop()
    case Event(Restart(fresh, waitDur), data) =>
      data.queueOpt.foreach(_.complete())
      setTimer("RestartLogin", Login, waitDur)
      log.info("Restarting, going to Inactive")
      goto(Inactive) using WithResumeData(if (fresh) None else data.resumeOpt)
  }

  /**
    * Handles all websocket messages received
    */
  def handleWsMessages: StateFunction = {
    case Event(Right(Ready(ReadyObject(ssrc, port, _, _))), data: WithHeartbeat) =>
      val connectionActor =
        context.actorOf(
          VoiceUDPHandler.props(address, ssrc, port, sendTo, sendSoundTo, serverId, userId),
          "VoiceUDPHandler"
        )
      connectionActor ! DoIPDiscovery(self)
      context.watchWith(connectionActor, ConnectionDied)
      val newData = WithUDPActor(
        ipData = None,
        receivedAck = data.receivedAck,
        previousNonce = data.previousNonce,
        ssrc = ssrc,
        connectionActor = connectionActor,
        queue = data.queue,
        resume = data.resume
      )

      stay using newData
    case Event(Right(Hello(heartbeatInterval)), WithQueue(queue, _)) =>
      self ! SendHeartbeat
      setTimer("SendHeartbeats", SendHeartbeat, (heartbeatInterval * 0.75).toInt.millis, repeat = true)
      stay using WithHeartbeat(
        ipData = None,
        receivedAck = true,
        previousNonce = None,
        queue = queue,
        resume = ResumeData(serverId, sessionId, token)
      )
    case Event(Right(HeartbeatACK(nonce)), data: WithUDPActor) => //TODO: Redesign data system to prevent this
      log.debug("Received HeartbeatACK")
      if (data.previousNonce.contains(nonce)) {
        stay using data.copy(receivedAck = true)
      } else throw new AckException(s"Received unknown nonce $nonce for HeartbeatACK")
    case Event(Right(HeartbeatACK(nonce)), data: WithHeartbeat) =>
      log.debug("Received HeartbeatACK")
      if (data.previousNonce.contains(nonce)) {
        stay using data.copy(receivedAck = true)
      } else throw new AckException(s"Received unknown nonce $nonce for HeartbeatACK")
    case Event(Right(SessionDescription(SessionDescriptionData(_, secretKey))), data: WithUDPActor) =>
      data.connectionActor ! StartConnection(secretKey)
      stay()
    case Event(Right(Speaking(SpeakingData(isSpeaking, delay, ssrc, Some(speakingUserId)))), _) =>
      sendTo.foreach(_ ! AudioAPIMessage.UserSpeaking(speakingUserId, ssrc, isSpeaking, delay, serverId, userId))
      stay()
    case Event(Right(IgnoreMessage12), _)        => stay()
    case Event(Right(IgnoreClientDisconnect), _) => stay()
  }

  initialize()
}
object VoiceWsHandler {
  def props(
      address: String,
      serverId: RawSnowflake,
      userId: UserId,
      sessionId: String,
      token: String,
      sendTo: Option[ActorRef],
      sendSoundTo: Option[ActorRef]
  )(implicit mat: Materializer): Props =
    Props(new VoiceWsHandler(address, serverId, userId, sessionId, token, sendTo, sendSoundTo))

  private case object SendIdentify
  private case object SendSelectProtocol
  private case object ConnectionDied

  /**
    * Sent to [[VoiceWsHandler]]. Used to set the client as speaking or not.
    */
  case class SetSpeaking(speaking: Boolean)

  private case class WithUDPActor(
      ipData: Option[IPData],
      receivedAck: Boolean,
      previousNonce: Option[Int],
      ssrc: Int,
      connectionActor: ActorRef,
      queue: SourceQueueWithComplete[Message],
      resume: ResumeData
  ) extends Data[ResumeData] {
    def resumeOpt:         Option[ResumeData]                       = Some(resume)
    override def queueOpt: Option[SourceQueueWithComplete[Message]] = Some(queue)
  }

  private case class IPData(address: String, port: Int)
  private case class WithHeartbeat(
      ipData: Option[IPData],
      receivedAck: Boolean,
      previousNonce: Option[Int],
      queue: SourceQueueWithComplete[Message],
      resume: ResumeData
  ) extends Data[ResumeData] {
    def resumeOpt:         Option[ResumeData]                       = Some(resume)
    override def queueOpt: Option[SourceQueueWithComplete[Message]] = Some(queue)
  }
}
