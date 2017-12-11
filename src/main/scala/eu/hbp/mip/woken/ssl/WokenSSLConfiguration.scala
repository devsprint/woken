/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.ssl

import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }

import eu.hbp.mip.woken.config.WokenConfig
import spray.io.ServerSSLEngineProvider

trait WokenSSLConfiguration {

  implicit val wokenSSLContext: SSLContext = {
    val keyStoreRes = WokenConfig.app.sslJksFilePath
    val password    = WokenConfig.app.sslJksPassword

    val keyStore = KeyStore.getInstance("jks")
    keyStore.load(getClass.getResourceAsStream(keyStoreRes), password.toCharArray)
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, password.toCharArray)
    val trustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    trustManagerFactory.init(keyStore)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers,
                 trustManagerFactory.getTrustManagers,
                 new SecureRandom)

    context
  }

  implicit val wokenSLLEngineProvider: ServerSSLEngineProvider =
    ServerSSLEngineProvider { engine =>
      engine.setEnabledCipherSuites(engine.getSupportedCipherSuites)
      engine.setEnabledProtocols(Array("SSLv3", "TLSv1.2"))
      engine
    }

}
