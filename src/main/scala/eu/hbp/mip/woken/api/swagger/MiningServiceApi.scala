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

package eu.hbp.mip.woken.api.swagger

import javax.ws.rs.Path

import akka.http.scaladsl.server.{ Directives, Route }
import eu.hbp.mip.woken.core.model.JobResult
import io.swagger.annotations._

// This trait documents the API, tries not to pollute the code with annotations

/**
  * Operations for data mining
  */
@Api(value = "/mining", consumes = "application/json", produces = "application/json")
trait MiningServiceApi extends Directives {

  @ApiOperation(
    value = "Run a data mining job",
    notes = "Run a data mining job and return id",
    httpMethod = "POST",
    consumes = "application/json",
    response = classOf[JobResult]
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "body",
                           value = "Process to execute",
                           required = true,
                           dataType = "eu.hbp.mip.messages.external.MiningQuery",
                           paramType = "body")
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(code = 201,
                      message = "Mining job initialized",
                      response = classOf[JobResult]),
      new ApiResponse(code = 401, message = "Authentication required.", response = classOf[String]),
      new ApiResponse(code = 403, message = "Authentication failed.", response = classOf[String]),
      new ApiResponse(code = 405, message = "Invalid mining job", response = classOf[String]),
      new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
    )
  )
  @Authorization(value = "BasicAuth")
  def mining: Route

  @Path("/experiment")
  @ApiOperation(
    value = "Run a data mining experiment",
    notes = "Run a data mining experiment and return id",
    httpMethod = "POST",
    consumes = "application/json",
    response = classOf[JobResult]
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "body",
                           value = "Process to execute",
                           required = true,
                           dataType = "eu.hbp.mip.messages.external.ExperimentQuery",
                           paramType = "body")
    )
  )
  @ApiResponses(
    Array(
      new ApiResponse(code = 201,
                      message = "Experiment initialized",
                      response = classOf[JobResult]),
      new ApiResponse(code = 401, message = "Authentication required.", response = classOf[String]),
      new ApiResponse(code = 403, message = "Authentication failed.", response = classOf[String]),
      new ApiResponse(code = 405, message = "Invalid Experiment", response = classOf[String]),
      new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
    )
  )
  @Authorization(value = "BasicAuth")
  def experiment: Route

  @Path("/methods")
  @ApiOperation(
    value = "Get mining method complete catalog",
    notes = "Get catalog containing available mining methods",
    httpMethod = "GET",
    consumes = "application/json",
    response = classOf[JobResult]
  )
  @ApiImplicitParams(Array())
  @ApiResponses(
    Array(
      new ApiResponse(code = 201,
                      message = "Experiment initialized",
                      response = classOf[spray.json.JsObject]),
      new ApiResponse(code = 401, message = "Authentication required.", response = classOf[String]),
      new ApiResponse(code = 403, message = "Authentication failed.", response = classOf[String]),
      new ApiResponse(code = 404, message = "Not Found", response = classOf[String]),
      new ApiResponse(code = 500, message = "Internal server error", response = classOf[String])
    )
  )
  @Authorization(value = "BasicAuth")
  def listMethods: Route

}
