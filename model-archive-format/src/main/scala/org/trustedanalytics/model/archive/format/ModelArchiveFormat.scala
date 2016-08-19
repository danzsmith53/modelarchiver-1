/**
 *  Copyright (c) 2015 Intel Corporation 
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.trustedanalytics.model.archive.format

import java.io._
import java.net.{ URL, URLClassLoader }
import java.nio.file.{ Files, Path }
import java.util.zip.{ ZipInputStream, ZipEntry, ZipOutputStream }
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.{ FileUtils, IOUtils }
import org.trustedanalytics.atk.event.EventLogging
import org.trustedanalytics.scoring.interfaces.{ModelLoader, Model}
//import java.lang.reflect._

/**
 * Read/write for publishing models
 */

object ModelArchiveFormat extends EventLogging {

  val modelReaderString = "modelReader"
  val BUFFER_SIZE = 4096

  /**
   * Write a Model to a our special format that can be read later by a Scoring Engine.
   *   
   * @param classLoaderFiles list of jars and other files for ClassLoader
   * @param modelLoaderClass class that implements the ModelLoader trait for instantiating the model during read()
   * @param outputStream location to store published model
   */
  def write(classLoaderFiles: List[File], modelLoaderClass: String,  outputStream: FileOutputStream): Unit = {
    val zipFile = new ZipOutputStream(new BufferedOutputStream(outputStream))

    try {
      classLoaderFiles.foreach((file: File) => {
        if (!file.isDirectory && file.exists()) {
          addFileToZip(zipFile, file)
        }
      })
      addByteArrayToZip(zipFile, modelReaderString + ".txt", modelLoaderClass.length, modelLoaderClass.getBytes("utf-8"))
    }
    catch {
      case e: Exception =>
        error("writing model failed", exception = e)
        throw e
    }
    finally {
      zipFile.finish()
      IOUtils.closeQuietly(zipFile)
    }
  }

  /**
   * Read a Model from our special format using a private ClassLoader.
   *   
   * May throw exception if version of archive doesn't match current library.
   *
   * @param modelArchiveInput location to read published model from
   * @param parentClassLoader parentClassLoader to use for the private ClassLoader
   * @return the instantiated Model   
   */
  def read(modelArchiveInput: File, parentClassLoader: ClassLoader): Model = {
    println("entered Read in Model Archiver")
    var zipInputStream: ZipInputStream = null
    var modelReaderName: String = null
    var urls = Array.empty[URL]
    var byteArray: Array[Byte] = null
    var libraryPaths: Set[String] = Set.empty[String]

    try {
      // Extract files to temporary directory so that dynamic library names are not changed
      val tempDirectory = getTemporaryDirectory
      zipInputStream = new ZipInputStream(new FileInputStream(modelArchiveInput))

      var entry = zipInputStream.getNextEntry
      while (entry != null) {
        val individualFile = entry.getName
        val file = extractFile(zipInputStream, tempDirectory.toString, individualFile)

        if (individualFile.contains(".jar")) {
          val url = file.toURI.toURL
          urls = urls :+ url
        }
        else if (individualFile.contains(".so")) {
          libraryPaths += getDirectoryPath(file)
        }
        else if (individualFile.contains(".dll")) {
          libraryPaths += getDirectoryPath(file)
        }
        else if (individualFile.contains(modelReaderString)) {
          val s = scala.io.Source.fromFile(tempDirectory.toString + "/" + individualFile).mkString
          modelReaderName = s.replaceAll("\n", "")
        }
        entry = zipInputStream.getNextEntry
      }

      val classLoader = new URLClassLoader(urls, parentClassLoader)
      val modelLoader = classLoader.loadClass(modelReaderName).newInstance()


      addToJavaLibraryPath(libraryPaths) //Add temporary directory to java.library.path
      //val c1 = modelLoader.getClass.getClassLoader
      //println(s"modelLoader class loader ${c1}")
      //c1.loadClass("org.trustedanalytics.scoring.interfaces.ModelLoader")
      modelLoader.asInstanceOf[ModelLoader].load(modelArchiveInput)
    }
    catch {
      case e: Exception =>
        error("reading model failed", exception = e)
        throw e
    }
    finally {
      IOUtils.closeQuietly(zipInputStream)
    }
  }

  /**
   * Write content to temporary file
   *
   * @param zipIn File content to write
   * @param tempDir Temporary directory
   * @param filePath File path
   *
   * @return Temporary file
   */
  def extractFile(zipIn: ZipInputStream, tempDir: String, filePath: String): File = {
    var file: File = null
    val fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
    var bufferedOutStream: BufferedOutputStream = null

    try {
      file = new File(tempDir, fileName)
      file.createNewFile()

      bufferedOutStream = new BufferedOutputStream(new FileOutputStream(file))
      val bytesIn: Array[Byte] = new Array[Byte](BUFFER_SIZE)
      var continueReading = true
      var read = 0
      while (continueReading) {
        read = zipIn.read(bytesIn)
        if (read != -1) {
          bufferedOutStream.write(bytesIn, 0, read)
        }
        else {
          continueReading = false
        }
      }
    }
    catch {
      case e: Exception =>
        error(s"reading model failed due to error extracting file: ${filePath}", exception = e)
        throw e
    }
    finally {
      bufferedOutStream.close()
    }
    file
  }

  /**
   * Add byte array contents to zip file using
   *
   * @param zipFile Zip File
   * @param entryName Name of entry to add
   * @param entrySize Size of entry
   * @param entryContent Content to add
   */
  def addByteArrayToZip(zipFile: ZipOutputStream, entryName: String, entrySize: Int, entryContent: Array[Byte]): Unit = {
    val modelEntry = new ZipEntry(entryName)
    modelEntry.setSize(entrySize)
    zipFile.putNextEntry(modelEntry)
    IOUtils.copy(new ByteArrayInputStream(entryContent), zipFile)
  }

  /**
   * Add file contents to the zip file
   *
   * @param zipFile Zip file
   * @param file File to add
   */
  def addFileToZip(zipFile: ZipOutputStream, file: File): Unit = {
    val fileEntry = new ZipEntry(file.getName)
    try {
      zipFile.putNextEntry(fileEntry)
      IOUtils.copy(new FileInputStream(file), zipFile)
    }
    catch {
      case e: Exception => {
        error("Failed to add the given file to zip ", exception = e)
      }
    }
  }

  def getTemporaryDirectory: Path = {
    try {
      val config = ConfigFactory.load(this.getClass.getClassLoader)
      val configKey = "atk.scoring-engine.tmpdir"

      val tmpModelDir = if (config.hasPath(configKey)) {
        val tmpDirStr: String = config.getString("atk.scoring-engine.tmpdir")
        val tmpDir = new File(tmpDirStr)
        if (!tmpDir.exists()) {
          tmpDir.mkdir()
        }
        tmpDir
      }
      else {
        val tmpDir = Files.createTempDirectory("tap-scoring-model")
        tmpDir.toFile
      }

      info(s"installing model to temporary directory:${tmpModelDir.getAbsolutePath}")
      sys.addShutdownHook(FileUtils.deleteQuietly(tmpModelDir)) // Delete temporary directory on exit
      tmpModelDir.toPath
    }
    catch {
      case e: Exception => {
        error("Failed to create temporary director for extracting model", exception = e)
        throw e
      }
    }
  }

  /**
   * Get directory path for input file
   *
   * @param file Input file
   * @return Returns parent directory if input is a file, or absolute path if input is a directory
   */
  private def getDirectoryPath(file: File): String = {
    if (file.isDirectory) {
      file.getAbsolutePath
    }
    else {
      file.getParent
    }
  }

  /**
   * Dynamically add library paths to java.library.path
   *
   * @param libraryPaths Library paths to add
   */
   def addToJavaLibraryPath(libraryPaths: Set[String]): Unit = {
    try {
      val usrPathField = classOf[ClassLoader].getDeclaredField("usr_paths")
      usrPathField.setAccessible(true)
      val newLibraryPaths = usrPathField.get(null).asInstanceOf[Array[String]].toSet ++ libraryPaths
      usrPathField.set(null, newLibraryPaths.toArray)
    }
    catch {
      case e: Exception =>
        error(s"reading model failed due to failure to set java.library.path: ${libraryPaths.mkString(",")}",
          exception = e)
        throw e
    }
  }
}
