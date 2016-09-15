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
import java.nio.file.{Paths, Files, Path}
import java.util.zip.{ ZipInputStream, ZipEntry, ZipOutputStream }
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.{ FileUtils, IOUtils }
import org.slf4j.LoggerFactory
import org.trustedanalytics.scoring.interfaces.{ModelReader, Model}
import scala.util.parsing.json._


/**
 * Read/write for publishing models
 */

object ModelArchiveFormat {

  private val logger = LoggerFactory.getLogger(this.getClass)
  val DESCRIPTOR_FILENAME = "descriptor.json"
  val BUFFER_SIZE = 4096
  val MODEL_READER_NAME = "modelLoaderClassName"

  /**
   * Write model using Model Archive Format.
   *   
   * @param dependencyFiles list of jars and other files for ClassLoader
   * @param modelReaderClassName class that implements the ModelLoader trait for instantiating the model during read()
   * @param outputStream location to store published model
   */
  def write(dependencyFiles: List[File], modelReaderClassName: String,  outputStream: FileOutputStream): Unit = {
    val zipFile = new ZipOutputStream(new BufferedOutputStream(outputStream))

    try {
      dependencyFiles.foreach((file: File) => {
        if (file.exists()) {
          addFileToZip(zipFile, file)
        }
      })
      val modelReaderClassNameJson = "{\"" + MODEL_READER_NAME + "\": \"" + modelReaderClassName + "\"}"
      addByteArrayToZip(zipFile, DESCRIPTOR_FILENAME, modelReaderClassNameJson.length, modelReaderClassNameJson.getBytes("utf-8"))
    }
    finally {
      zipFile.finish()
      IOUtils.closeQuietly(zipFile)
    }
  }

  /**
   * Read model from Model Archive Format.
   *   
   * May throw exception if version of archive doesn't match current library.
   *
   * @param modelArchiveInput location to read published model from
   * @param parentClassLoader parentClassLoader to use for the private ClassLoader.
   * @return the instantiated Model   
   */
  def read(modelArchiveInput: File, parentClassLoader: ClassLoader, bufSize: Option[Int]): Model = {
    logger.info("Entered Read in Model Archive Format")
    var zipInputStream: ZipInputStream = null
    var modelReaderName: String = null
    var urls = Array.empty[URL]
    var libraryPaths: Set[String] = Set.empty[String]

    try {
      // Extract files to temporary directory so that dynamic library names are not changed
      val tempDirectory = getTemporaryDirectory
      zipInputStream = new ZipInputStream(new FileInputStream(modelArchiveInput))

      var entry = zipInputStream.getNextEntry
      while (entry != null) {
        val individualFile = entry.getName
        val file = extractFile(zipInputStream, tempDirectory.toString, individualFile, bufSize)

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
        else if (individualFile.contains(DESCRIPTOR_FILENAME)) {
          val jsonString = scala.io.Source.fromFile(Paths.get(tempDirectory.toString, individualFile).toString).mkString
          val parsed = JSON.parseFull(jsonString)
          var jsonMap: Map[String, String] = null
          parsed match {
            case Some(m) => jsonMap = m.asInstanceOf[Map[String, String]]
            case None => logger.error("unable to find the model reader class name")
          }
          modelReaderName = jsonMap(MODEL_READER_NAME)
        }
        //ignore the other files. They are used in the Model Reader operation
        entry = zipInputStream.getNextEntry
      }

      val classLoader = new URLClassLoader(urls, parentClassLoader)
      val modelReader = classLoader.loadClass(modelReaderName).newInstance()
      addToJavaLibraryPath(libraryPaths) //Add temporary directory to java.library.path
      modelReader.asInstanceOf[ModelReader].read(modelArchiveInput)
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
  def extractFile(zipIn: ZipInputStream, tempDir: String, filePath: String, bufferSize: Option[Int]): File = {
    var file: File = null
    val fileName = filePath.substring(filePath.lastIndexOf("/") + 1)
    var bufferedOutStream: BufferedOutputStream = null
    var bytesIn: Array[Byte] = null

    try {
      file = new File(tempDir, fileName)
      file.createNewFile()

      bufferedOutStream = new BufferedOutputStream(new FileOutputStream(file))
      bufferSize match {
        case Some(size) => bytesIn = new Array[Byte](size)
        case None => bytesIn = new Array[Byte](BUFFER_SIZE)
      }

      var read = zipIn.read(bytesIn)
      while (read != -1){
        bufferedOutStream.write(bytesIn, 0, read)
        read = zipIn.read(bytesIn)
      }
    }
    finally {
      bufferedOutStream.close()
    }
    file
  }

  /**
   * Add byte array contents to zip file using
   *
   * @param zipStream Zip Stream
   * @param entryName Name of entry to add
   * @param entrySize Size of entry
   * @param entryContent Content to add
   */
  def addByteArrayToZip(zipStream: ZipOutputStream, entryName: String, entrySize: Int, entryContent: Array[Byte]): Unit = {
    val modelEntry = new ZipEntry(entryName)
    modelEntry.setSize(entrySize)
    zipStream.putNextEntry(modelEntry)
    IOUtils.copy(new ByteArrayInputStream(entryContent), zipStream)
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
  }

  def getTemporaryDirectory: Path = {
    try {
      val config = ConfigFactory.load(this.getClass.getClassLoader)
      val configKey = "trustedanalytics.scoring-engine.tmpdir"

      val tmpModelDir = if (config.hasPath(configKey)) {
        val tmpDirStr: String = config.getString(configKey)
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

      logger.info(s"installing model to temporary directory:${tmpModelDir.getAbsolutePath}")
      sys.addShutdownHook(FileUtils.deleteQuietly(tmpModelDir)) // Delete temporary directory on exit
      tmpModelDir.toPath
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
        error(s"reading model failed due to failure to set java.library.path: ${libraryPaths.mkString(",")}")
        throw e
    }
  }
}
