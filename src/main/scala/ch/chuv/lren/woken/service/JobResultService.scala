/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.service

import cats.effect.Effect
import ch.chuv.lren.woken.core.model.jobs.JobResult
import ch.chuv.lren.woken.dao.JobResultRepository

import scala.language.higherKinds

/**
  * Service that provides access to the job results.
  *
  * @param repository where we get our data
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
class JobResultService[F[_]: Effect](repository: JobResultRepository[F]) {

  def put(result: JobResult): F[JobResult] = repository.put(result)

  def get(jobId: String): F[Option[JobResult]] = repository.get(jobId)

}

object JobResultService {

  def apply[F[_]: Effect](repo: JobResultRepository[F]): JobResultService[F] =
    new JobResultService(repo)

}
