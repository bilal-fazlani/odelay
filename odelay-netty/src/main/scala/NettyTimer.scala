package odelay.netty

import io.netty.util.{HashedWheelTimer, Timeout, Timer => NTimer, TimerTask}
import io.netty.util.concurrent.{EventExecutorGroup, Future => NFuture}
import odelay.{Delay, PeriodicDelay, PeriodicPromisingDelay, PromisingDelay, Timer}
import odelay.jdk.JdkTimer
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

class NettyGroupTimer(grp: EventExecutorGroup, interruptOnCancel: Boolean = NettyTimer.interruptOnCancel)
    extends Timer {

  def apply[T](delay: FiniteDuration, op: => T): Delay[T] =
    new PromisingDelay[T] {
      val sf: Option[NFuture[_]] =
        try {
          Some(
            grp.schedule(
              new Runnable {
                def run = completePromise(op)
              },
              delay.length,
              delay.unit
            )
          )
        } catch {
          case NonFatal(e) =>
            failPromise(e)
            None
        }

      def cancel() = sf.filterNot(_.isCancelled).foreach { f =>
        f.cancel(interruptOnCancel)
        cancelPromise()
      }
    }

  def apply[T](delay: FiniteDuration, every: FiniteDuration, op: => T): PeriodicDelay[T] =
    new PeriodicPromisingDelay[T](every) {
      val sf: Option[NFuture[_]] =
        try {
          Some(
            grp.scheduleWithFixedDelay(
              new Runnable {
                def run = if (promiseIncomplete) op
              },
              delay.toUnit(every.unit).toLong,
              every.length,
              every.unit
            )
          )
        } catch {
          case NonFatal(e) =>
            failPromise(e)
            None
        }

      def cancel() = sf.filterNot(_.isCancelled).foreach { f =>
        f.cancel(interruptOnCancel)
        cancelPromise()
      }
    }

  def stop() = if (!grp.isShuttingDown()) grp.shutdownGracefully()
}

class NettyTimer(underlying: NTimer = new HashedWheelTimer) extends Timer {

  def apply[T](delay: FiniteDuration, op: => T): Delay[T] =
    new PromisingDelay[T] {
      val to =
        try {
          Some(
            underlying.newTimeout(
              new TimerTask {
                def run(timeout: Timeout) =
                  completePromise(op)
              },
              delay.length,
              delay.unit
            )
          )
        } catch {
          case NonFatal(e) =>
            failPromise(e)
            None
        }

      def cancel() = to.filterNot(_.isCancelled).foreach { f =>
        f.cancel()
        cancelPromise()
      }
    }

  def apply[T](delay: FiniteDuration, every: FiniteDuration, op: => T): PeriodicDelay[T] =
    new PeriodicPromisingDelay[T](every) {
      var nextDelay: Option[Delay[T]] = None
      val to =
        try {
          Some(
            underlying.newTimeout(
              new TimerTask {
                def run(timeout: Timeout) = loop()
              },
              delay.length,
              delay.unit
            )
          )
        } catch {
          case NonFatal(e) =>
            failPromise(e)
            None
        }

      def loop() =
        if (promiseIncomplete) {
          op
          nextDelay = Some(apply(every, every, op))
        }

      def cancel() =
        to.filterNot(_.isCancelled).foreach { f =>
          synchronized {
            f.cancel()
            nextDelay.foreach(_.cancel())
            cancelPromise()
          }
        }
    }

  def stop(): Unit = underlying.stop()
}

object NettyTimer {
  val interruptOnCancel = true

  def groupTimer(grp: EventExecutorGroup) =
    new NettyGroupTimer(grp)

  /** @return a _new_ NettyTimer backed by a HashedWheelTimer */
  def newTimer: Timer = new NettyTimer(new HashedWheelTimer(JdkTimer.threadFactory, 10, TimeUnit.MILLISECONDS))
}
