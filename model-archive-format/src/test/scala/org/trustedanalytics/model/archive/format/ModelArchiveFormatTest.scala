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
import java.net.URLClassLoader
import java.nio.charset.Charset
import java.util.zip.{ ZipOutputStream, ZipInputStream }
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.scalatest.WordSpec
import org.apache.commons.io.{ FileUtils, IOUtils }
import org.scalatest.Assertions._
import org.trustedanalytics.scoring.interfaces.{Field, ModelMetaDataArgs, Model, ModelLoader}

class ModelArchiveFormatTest extends WordSpec {

  "ModelArchiveFormat" should {
    "create a zip of given files and place into an output stream" in {
      val testZip = File.createTempFile("test", ".jar")
      val testZip2 = File.createTempFile("test2", ".jar")
      val modelFile= File.createTempFile("Model", ".txt")
      val fileList = testZip :: testZip2 :: modelFile :: Nil
      var zipFile: File = null
      var zipOutput: FileOutputStream = null
      var counter = 0
      val modelReader = "TestModelReader"

      val model = "This is a test Model"

      var testZipFileStream: ZipInputStream = null
      try {
        zipFile = File.createTempFile("TestZip", ".mar")
        zipOutput = new FileOutputStream(zipFile)
        ModelArchiveFormat.write(fileList, modelReader, zipOutput)
        val tempDirectory = ModelArchiveFormat.getTemporaryDirectory
        testZipFileStream = new ZipInputStream(new FileInputStream(new File(zipFile.getAbsolutePath)))

        var entry = testZipFileStream.getNextEntry

        while (entry != null) {
          val individualFile = entry.getName
          if (individualFile.contains(".jar")) {
            counter = counter + 1
          }
          else if (individualFile.contains("modelReader")) {
            val file = ModelArchiveFormat.extractFile(testZipFileStream, tempDirectory.toString, individualFile)
            val s = scala.io.Source.fromFile(tempDirectory.toString + "/" + individualFile).mkString
            val modelName = s.replaceAll("\n", "")
            assert(modelName.equals(modelReader))
          }
          else if (individualFile.contains("modelData")) {
            val file = ModelArchiveFormat.extractFile(testZipFileStream, tempDirectory.toString, individualFile)
            val byteArray = scala.io.Source.fromFile(tempDirectory.toString + "/" + individualFile).map(_.toByte).toArray
            assert(byteArray.length == model.getBytes(Charset.forName("utf-8")).length)
          }
          entry = testZipFileStream.getNextEntry
        }
        assert(counter == 2)
      }
      finally {
        FileUtils.deleteQuietly(zipFile)
        FileUtils.deleteQuietly(testZip)
      }
    }
  }

  "create a model given a mar file" in {
    val testZipFile = File.createTempFile("TestZip", ".mar")
    val testJarFile = File.createTempFile("test", ".jar")
    val testZipArchive = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(testZipFile)))
    var modelDataFile = File.createTempFile("modelData", ".txt")
    var modelLoaderFile = File.createTempFile("modelReader", ".txt")

    try {
      ModelArchiveFormat.addFileToZip(testZipArchive, testJarFile)
      ModelArchiveFormat.addByteArrayToZip(testZipArchive, "modelData.txt", 128, "This is dummy model data".getBytes("utf-8"))
      ModelArchiveFormat.addByteArrayToZip(testZipArchive, "modelReader.txt", 256, "org.trustedanalytics.model.archive.format.TestModelReader".getBytes("utf-8"))

      testZipArchive.finish()
      IOUtils.closeQuietly(testZipArchive)

      val testModel = ModelArchiveFormat.read(testZipFile, this.getClass.getClassLoader)

      assert(testModel.isInstanceOf[Model])
      assert(testModel != null)
    }
    catch {
      case e: Exception =>
        throw e
    }
    finally {
      FileUtils.deleteQuietly(modelLoaderFile)
      FileUtils.deleteQuietly(modelDataFile)
      FileUtils.deleteQuietly(testZipFile)
      FileUtils.deleteQuietly(testJarFile)
    }
  }
}

class TestModelReader extends ModelLoader {

  private var testModel: TestModel = _

  override def load(modelArchiveZip: File): Model = {
    testModel = new TestModel
    testModel.asInstanceOf[Model]
  }
}

class TestModel() extends Model {

  override def score(data: Array[Any]): Array[Any] = {
    var score = Array[Any]()
    score = score :+ 2
    score
  }

  override def input: Array[Field] = {
    var input = Array[Field]()
    input = input :+ Field("input", "Float")
    input
  }

  override def output: Array[Field] = {
    var output = Array[Field]()
    output = output :+ Field("output", "Float")
    output
  }

  override def modelMetadata(): ModelMetaDataArgs = {
    new ModelMetaDataArgs("Dummy Model", "dummy class", "dummy reader", Map("created_on" -> "Jan 29th 2016"))
  }
}

