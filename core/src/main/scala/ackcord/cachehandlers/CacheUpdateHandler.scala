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
package ackcord.cachehandlers

import akka.event.LoggingAdapter

/**
  * A [[CacheHandler]] for updates.
  */
trait CacheUpdateHandler[-Obj] extends CacheHandler[Obj]
object CacheUpdateHandler {
  def updateHandler[Obj](f: (CacheSnapshotBuilder, Obj, LoggingAdapter) => Unit): CacheUpdateHandler[Obj] =
    new CacheUpdateHandler[Obj] {
      override def handle(builder: CacheSnapshotBuilder, obj: Obj)(implicit log: LoggingAdapter): Unit =
        f(builder, obj, log)
    }

  implicit def seqHandler[Obj](implicit objHandler: CacheUpdateHandler[Obj]): CacheUpdateHandler[Seq[Obj]] =
    updateHandler((builder, obj, log) => obj.foreach(objHandler.handle(builder, _)(log)))

  def handleUpdateLog[Obj](builder: CacheSnapshotBuilder, obj: Obj, log: LoggingAdapter)(
      implicit handler: CacheUpdateHandler[Obj]
  ): Unit = handler.handle(builder, obj)(log)
}
