/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.znet2

import java.io.{ByteArrayInputStream, File}

import com.waz.specs.ZSpec
import com.waz.utils.{JsonDecoder, JsonEncoder}
import com.waz.znet2.http.HttpClient.{CustomErrorConstructor, Progress, ProgressCallback}
import com.waz.znet2.http.Request.UrlCreator
import com.waz.znet2.http._
import okhttp3.mockwebserver.{MockResponse, MockWebServer}
import okio.{Buffer, Okio}
import org.json.JSONObject
import com.waz.znet2.http.HttpClient.AutoDerivationOld._
import org.junit.{After, Before, Test}
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkSecurityPolicy

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global

@RunWith(classOf[RobolectricTestRunner])
@Config(shadows = Array(classOf[ShadowNetworkSecurityPolicy]))
class HttpClientSpec extends ZSpec {

  case class FooError(description: String)

  implicit val fooErrorConstructor: CustomErrorConstructor[FooError] = new CustomErrorConstructor[FooError] {
    override def constructFrom(error: HttpClient.HttpClientError): FooError = FooError("unknown error")
  }

  implicit val fooErrorEncoder: JsonEncoder[FooError] = new JsonEncoder[FooError] {
    override def apply(data: FooError): JSONObject = JsonEncoder { o =>
      o.put("description", data.description)
    }
  }

  implicit val fooErrorDecoder: JsonDecoder[FooError] = new JsonDecoder[FooError] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject): FooError = {
      FooError(decodeString('description))
    }
  }

  val tempFileBodyDeserializer: RawBodyDeserializer[File] = RawBodyDeserializer.createFileRawBodyDeserializer(
    new File(s"${System.getProperty("java.io.tmpdir")}/http_client_tmp_${System.currentTimeMillis()}")
  )

  case class Foo(a: Int, b: String)

  implicit val fooEncoder: JsonEncoder[Foo] = new JsonEncoder[Foo] {
    override def apply(data: Foo): JSONObject = JsonEncoder { o =>
      o.put("a", data.a)
      o.put("b", data.b)
    }
  }

  implicit val fooDecoder: JsonDecoder[Foo] = new JsonDecoder[Foo] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject): Foo = {
      Foo(decodeInt('a), 'b)
    }
  }

  private def createClient(): HttpClientOkHttpImpl = {
    new HttpClientOkHttpImpl(HttpClientOkHttpImpl.createOkHttpClient())
  }

  private var mockServer: MockWebServer = _

  private implicit val urlCreator: UrlCreator = UrlCreator.create(relativeUrl => mockServer.url(relativeUrl).url())

  @Before
  def setUp(): Unit = {
    mockServer = new MockWebServer()
    mockServer.start()
  }

  @After
  def tearDown(): Unit = {
    mockServer.shutdown()
  }

  @Test
  def `return_http_response_when_server_is_responding`(): Unit = {   //"return http response when server is responding."
    val testResponseCode = 201
    val testBodyStr = "test body"

    mockServer.enqueue(
      new MockResponse()
        .setResponseCode(testResponseCode)
        .setHeader("Content-Type", "text/plain")
        .setBody(testBodyStr)
    )

    val client = createClient()
    val request = Request.Get("/test")

    var response: Response[Array[Byte]] = null

    noException shouldBe thrownBy { response = result { client.result[EmptyBody, Response[Array[Byte]]](request) } }

    response.code                       shouldBe testResponseCode
    new String(response.body)           shouldBe testBodyStr
  }

  @Test
  def `return_decoded_response_body_[Foo]_when_server_is_responding`(): Unit = {
    val testResponseCode = 201
    val testResponseObject = Foo(1, "ok")
    val testResponseBodyStr = fooEncoder(testResponseObject).toString

    mockServer.enqueue(
      new MockResponse()
        .setResponseCode(testResponseCode)
        .setHeader("Content-Type", "application/json")
        .setBody(testResponseBodyStr)
    )

    val client = createClient()
    val request = Request.Get("/test")

    val responseObjectFuture = client.result[EmptyBody, Foo](request)
    var responseObject: Foo = null
    noException shouldBe thrownBy {
      responseObject = result { responseObjectFuture }
    }

    responseObject shouldBe testResponseObject
  }

  @Test
def `return_decoded_response_body_[File]_when_server_is_responding`(): Unit = {
    val testResponseCode = 201
    val testResponseObject = Foo(1, "ok")
    val testResponseBodyStr = fooEncoder(testResponseObject).toString

    mockServer.enqueue(
      new MockResponse()
        .setResponseCode(testResponseCode)
        .setHeader("Content-Type", "application/json")
        .setBody(testResponseBodyStr)
    )

    val client = createClient()
    val request = Request.Get("/test")
    implicit val deserializer: RawBodyDeserializer[File] = tempFileBodyDeserializer

    val responseObjectFuture = client.result[EmptyBody, File](request)
    var responseFile: File = null
    noException shouldBe thrownBy {
      responseFile = result { responseObjectFuture }
    }

    responseFile.exists() shouldBe true
    scala.io.Source.fromFile(responseFile).mkString shouldBe testResponseBodyStr
  }

  @Test
  def `return_decoded_response_body_[Right[_, Foo]]_when_response_code_is_successful`(): Unit = {
    val testResponseCode = 201
    val testResponseObject = Foo(1, "ok")
    val testResponseBodyStr = fooEncoder(testResponseObject).toString

    mockServer.enqueue(
      new MockResponse()
        .setResponseCode(testResponseCode)
        .setHeader("Content-Type", "application/json")
        .setBody(testResponseBodyStr)
    )

    val client = createClient()
    val request = Request.Get("/test")

    val responseObjectFuture = client.resultWithDecodedErrorSafe[EmptyBody, FooError, Foo](request)
    var responseObject: Either[FooError, Foo] = null
    noException shouldBe thrownBy {
      responseObject = result { responseObjectFuture }
    }

    responseObject shouldBe Right(testResponseObject)
  }

  @Test
  def `return_decoded_response_body_[Left[FooError, _]]_when_response_code_is_unsuccessful`(): Unit = {
    val testResponseCode = 500
    val testResponseObject = FooError("test descr")
    val testResponseBodyStr = fooErrorEncoder(testResponseObject).toString

    mockServer.enqueue(
      new MockResponse()
        .setResponseCode(testResponseCode)
        .setHeader("Content-Type", "application/json")
        .setBody(testResponseBodyStr)
    )

    val client = createClient()
    val request = Request.Get("/test")

    val responseObjectFuture = client.resultWithDecodedErrorSafe[EmptyBody, FooError, Foo](request)
    var responseObject: Either[FooError, Foo] = null
    noException shouldBe thrownBy {
      responseObject = result { responseObjectFuture }
    }

    responseObject shouldBe Left(testResponseObject)
  }

  @Test
  def `should_execute_upload_request_and_call_progress_callback_when_server_is_responding`(): Unit = {
    val testResponseCode = 201
    val testRequestBody = Array.fill[Byte](100000)(1)

    mockServer.enqueue(
      new MockResponse()
        .setResponseCode(testResponseCode)
        .setHeader("Content-Type", "application/octet-stream")
        .setBody("we do not care")
    )

    val client = createClient()
    val request = Request.Post("/test", body = testRequestBody)

    val progressAcc = ArrayBuffer.empty[Progress]
    val callback = new ProgressCallback {
      override def updated(progress: Long, total: Option[Long]): Unit = progressAcc.append(Progress(progress, total))
    }
    noException shouldBe thrownBy {
      await { client.result[Array[Byte], Response[String]](request, uploadCallback = Some(callback)) }
    }

    checkProgressSequence(
      progressAcc.toList,
      contentLength = testRequestBody.length
    )
  }

  @Test
  def `should_execute_download_request_and_call_progress_callback_when_server_is_responding`(): Unit = {
    val testResponseCode = 200
    val testResponseBody = Array.fill[Byte](100000)(1)
    val buffer = new Buffer()
    buffer.writeAll(Okio.source(new ByteArrayInputStream(testResponseBody)))

    mockServer.enqueue(
      new MockResponse()
        .setResponseCode(testResponseCode)
        .setHeader("Content-Type", "application/octet-stream")
        .setBody(buffer)
    )

    val client = createClient()
    val request = Request.Get("/test")
    implicit val deserializer: RawBodyDeserializer[File] = tempFileBodyDeserializer

    val progressAcc = ArrayBuffer.empty[Progress]
    val callback = new ProgressCallback {
      override def updated(progress: Long, total: Option[Long]): Unit = progressAcc.append(Progress(progress, total))
    }
    noException shouldBe thrownBy {
      await { client.result[EmptyBody, Response[File]](request, downloadCallback = Some(callback)) }
    }

    checkProgressSequence(
      progressAcc.toList,
      contentLength = testResponseBody.length
    )
  }

  def checkProgressSequence(list: List[Progress], contentLength: Long): Unit =
    withClue(s"Progress sequence: $list") {
      list.head.progress shouldBe 0
      list.last.isCompleted shouldBe true
      list foreach { p => p.progress should be <= p.total.getOrElse(0L) }
      list zip list.tail foreach { case (prev, curr) => prev.progress should be < curr.progress }
    }

}
