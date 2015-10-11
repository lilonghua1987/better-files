package better.files

import java.nio.file.{StandardWatchEventKinds => EventType, WatchKey, Path, WatchEvent}

import akka.actor

/**
 * An actor that can watch a file or a directory
 * Instead of directly calling the constructor of this, call file.newWatcher to create the actor
 *
 * @param file watch this file (or directory)
 * @param maxDepth In case of directories, how much depth should we watch
 */
class FileWatcher(file: File, maxDepth: Int = 0) extends actor.Actor {

  def this(file: File, recursive: Boolean) = this(file, if (recursive) Int.MaxValue else 0)

  protected[this] val service = file.newWatchService

  protected[this] val callbacks = newMultiMap[FileWatcher.Event, FileWatcher.Callback]

  protected[this] val watcher = new Thread {
    override def run() = scala.util.control.Exception.ignoring(classOf[InterruptedException]) {
      Iterator.continually(service.take()) foreach process
    }

    def process(key: WatchKey) = {
      import scala.collection.JavaConversions._
      val root = key.watchable().asInstanceOf[Path]
      key.pollEvents()/*.filter(_ != EventType.OVERFLOW)*/ foreach {event =>
        val relativePath = event.context().asInstanceOf[Path]
        self ! (event.kind() -> File(root resolve relativePath))
      }
      key.reset()
    }

    setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler {
      override def uncaughtException(thread: Thread, exception: Throwable) = self ! actor.Status.Failure(exception)
    })
  }

  protected[this] def watch(aFile: File): Unit = if (aFile.isDirectory) {
    for {
      f <- aFile.walk(maxDepth) if f.isDirectory && f.exists
    } f.path.register(service, FileWatcher.allEvents: _*)
  } else if (aFile.exists) {   // There is no way to watch a regular file; so watch its parent instead
    aFile.parent.path.register(service, FileWatcher.allEvents: _*)
  }

  override def preStart() = {
    super.preStart()
    self ! on(EventType.ENTRY_CREATE)(watch)    //TODO: correct maxDepth?
    watch(file)
    watcher.setDaemon(true)
    watcher.start()
  }

  override def receive = {
    case (event: FileWatcher.Event @unchecked, target: File) if (callbacks contains event) && (file.isDirectory || (file isSamePathAs target)) =>
      callbacks(event) foreach {f => f(event -> target)}
    case FileWatcher.RegisterCallback(events, callback) => events foreach {event => callbacks.addBinding(event, callback)}
    case FileWatcher.RemoveCallback(event, callback) => callbacks.removeBinding(event, callback)
  }

  override def postStop() = {
    super.postStop()
    watcher.interrupt()
    service.close()
  }
}

object FileWatcher {
  type Event = WatchEvent.Kind[Path]
  type FileEvent = (Event, File)
  type Callback = PartialFunction[FileEvent, Unit]

  case class RegisterCallback(events: Seq[Event], callback: Callback)
  case class RemoveCallback(event: Event, callback: Callback)

  private[FileWatcher] val allEvents = Seq(EventType.ENTRY_CREATE, EventType.ENTRY_MODIFY, EventType.ENTRY_DELETE)
}
