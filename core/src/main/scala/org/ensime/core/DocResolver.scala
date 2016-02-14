// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.core

import akka.actor._
import akka.event.slf4j.SLF4JLogging
import com.google.common.io.ByteStreams
import java.io.{ File, IOException }
import java.util.jar.JarFile
import org.ensime.api._
import scala.collection.mutable

class DocResolver(
    prefix: String,
    forceJavaVersion: Option[String] // for testing
)(
    implicit
    config: EnsimeConfig
) extends Actor with ActorLogging with DocUsecaseHandling {

  var htmlToJar = Map.empty[String, File]
  var jarNameToJar = Map.empty[String, File]
  var docTypes = Map.empty[String, DocType]

  sealed trait DocType
  case object Javadoc extends DocType
  case object Javadoc8 extends DocType
  case object Scaladoc extends DocType

  // In javadoc docs, index.html has a comment that reads 'Generated by javadoc'
  private val JavadocComment = """Generated by javadoc (?:\(([0-9\.]+))?""".r.unanchored

  override def preStart(): Unit = {
    // On initialisation, do a fast scan (< 1s for 50 jars) to determine
    // the package contents of each jar, and whether it's a javadoc or
    // scaladoc.

    for (
      jarFile <- config.allDocJars if jarFile.exists()
    ) {
      try {
        val jar = new JarFile(jarFile)
        val jarFileName = jarFile.getName
        jarNameToJar += jarFileName -> jarFile
        docTypes += (jarFileName -> Scaladoc)
        val enumEntries = jar.entries()
        while (enumEntries.hasMoreElements) {
          val entry = enumEntries.nextElement()
          if (!entry.isDirectory) {
            val f = new File(entry.getName)
            val dir = f.getParent
            if (dir != null) {
              htmlToJar += entry.getName -> jarFile
            }
            // Check for javadocs
            if (entry.getName == "index.html") {
              val bytes = ByteStreams.toByteArray(jar.getInputStream(entry))
              new String(bytes) match {
                case JavadocComment(version: String) if version.startsWith("1.8") =>
                  docTypes += jarFileName -> Javadoc8
                case JavadocComment(_*) =>
                  docTypes += jarFileName -> Javadoc
                case _ =>
              }
            }
          }
        }
      } catch {
        case e: IOException =>
          // continue regardless
          log.error("Failed to process doc jar: " + jarFile.getName, e)
      }
    }
  }

  private def javaFqnToPath(fqn: DocFqn): String = {
    if (fqn.typeName == "package") {
      fqn.pack.replace(".", "/") + "/package-summary.html"
    } else {
      fqn.pack.replace(".", "/") + "/" + fqn.typeName + ".html"
    }
  }

  def scalaFqnToPath(fqn: DocFqn): String = {
    if (fqn.typeName == "package") {
      fqn.pack.replace(".", "/") + "/package.html"
    } else fqn.pack.replace(".", "/") + "/" + fqn.typeName + ".html"
  }

  private def makeLocalUri(jar: File, sig: DocSigPair): String = {
    val jarName = jar.getName
    val docType = docTypes(jarName)
    val java = docType == Javadoc || docType == Javadoc8
    if (java) {
      val path = javaFqnToPath(sig.java.fqn)
      val anchor = sig.java.member.map { s =>
        "#" + { if (docType == Javadoc8) toJava8Anchor(s) else s }
      }.getOrElse("")
      s"$prefix/$jarName/$path$anchor"
    } else {
      val scalaSig = maybeReplaceWithUsecase(jar, sig.scala)
      val anchor = scalaSig.fqn.mkString +
        scalaSig.member.map("@" + _).getOrElse("")
      s"$prefix/$jarName/index.html#$anchor"
    }
  }

  private val PackRegexp = """^((?:[a-z0-9]+\.)+)""".r

  private def guessJar(sig: DocSigPair): Option[File] = {
    htmlToJar.get(scalaFqnToPath(sig.scala.fqn))
      .orElse(htmlToJar.get(javaFqnToPath(sig.java.fqn)))
  }

  private def resolveLocalUri(sig: DocSigPair): Option[String] = {
    guessJar(sig) match {
      case Some(jar) =>
        log.debug(s"Resolved to jar: $jar")
        Some(makeLocalUri(jar, sig))
      case _ =>
        log.debug(s"Failed to resolve doc jar for: $sig")
        None
    }
  }

  // Javadoc 8 changed the anchor format to remove illegal
  // url characters: parens, commas, brackets.
  // See https://bugs.eclipse.org/bugs/show_bug.cgi?id=432056
  // and https://bugs.openjdk.java.net/browse/JDK-8025633
  private val Java8Chars = """(?:, |\(|\)|\[\])""".r
  private def toJava8Anchor(anchor: String): String = {
    Java8Chars.replaceAllIn(anchor, { m =>
      anchor(m.start) match {
        case ',' => "-"
        case '(' => "-"
        case ')' => "-"
        case '[' => ":A"
      }
    })
  }

  private def toAndroidAnchor(anchor: String): String = anchor.replace(",", ", ")

  private def resolveWellKnownUri(sig: DocSigPair): Option[String] = {
    if (sig.java.fqn.javaStdLib) {
      val path = javaFqnToPath(sig.java.fqn)
      val rawVersion = forceJavaVersion.getOrElse(scala.util.Properties.javaVersion)
      val version =
        if (rawVersion.startsWith("1.8")) "8" else if (rawVersion.startsWith("1.7")) "7" else "6"
      val anchor = sig.java.member.map {
        m => "#" + { if (version == "8") toJava8Anchor(m) else m }
      }.getOrElse("")
      Some(s"http://docs.oracle.com/javase/$version/docs/api/$path$anchor")

    } else if (sig.java.fqn.androidStdLib) {
      val path = javaFqnToPath(sig.java.fqn)
      val anchor = sig.java.member.map { m => "#" + toAndroidAnchor(m) }.getOrElse("")
      Some(s"http://developer.android.com/reference/$path$anchor")

    } else None
  }

  def resolve(sig: DocSigPair): Option[String] = resolveLocalUri(sig) orElse resolveWellKnownUri(sig)

  // for java stuff, really
  def resolve(sig: DocSig): Option[String] = resolve(DocSigPair(sig, sig))

  def receive: Receive = {
    case p: DocSigPair =>
      val response = resolve(p) match {
        case Some(path) => StringResponse(path)
        case None => FalseResponse
      }
      sender() ! response
  }

}
object DocResolver {
  def apply(
    prefix: String = "docs",
    java: Option[String] = None
  )(
    implicit
    config: EnsimeConfig
  ): Props = Props(classOf[DocResolver], prefix, java, config)
}
