package ggd.utils

import java.security.cert.X509Certificate

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.{ConnectionContext, Http}
import javax.net.ssl.{KeyManager, SSLContext, X509TrustManager}
import spray.json.DefaultJsonProtocol

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class RAWUtils(implicit actorSystem: ActorSystem) extends DefaultJsonProtocol {
  val pathQuery: String = "/1/public/query"
  val pathCreateView: String = "/1/public/views/"

  private val trustfulSslContext: SSLContext = {

    object NoCheckX509TrustManager extends X509TrustManager {
      override def checkClientTrusted(chain: Array[X509Certificate], authType: String) = ()

      override def checkServerTrusted(chain: Array[X509Certificate], authType: String) = ()

      override def getAcceptedIssuers = Array[X509Certificate]()
    }

    val context = SSLContext.getInstance("TLS")
    context.init(Array[KeyManager](), Array(NoCheckX509TrustManager), null)
    context
  }


  def createResquestView(RAWuri: String, viewName: String, RAWDataQuery: String, accessToken: String): HttpRequest = {
    val authorization = "Bearer " ++ accessToken
    HttpRequest(
      method = HttpMethods.PUT,
      uri = RAWuri ++ pathCreateView ++ viewName,
      entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, RAWDataQuery),
      headers = List(RawHeader("Authorization", authorization))//List(authorization)
    )//.withHeaders()
  }

  //functions for calling RAW Labs REST API
    def createViews(RAWuri: String, viewName: String, RAWDataQuery: String, accessToken: String): Unit = {
      val request = createResquestView(RAWuri, viewName, RAWDataQuery, accessToken)
      val noCertificateCheckContext = ConnectionContext.https(trustfulSslContext)
      val res = Await.result(Http().singleRequest(request, noCertificateCheckContext), Duration.Inf)
      val x = res.entity.withContentType(ContentTypes.`application/json`)
      val stringjson = Unmarshal(x).to[String]
      println(res)
      println(x)
      println(stringjson)
    }


}
