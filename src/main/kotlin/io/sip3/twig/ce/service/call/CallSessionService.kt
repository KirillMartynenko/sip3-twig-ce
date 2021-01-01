/*
 * Copyright 2018-2021 SIP3.IO, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sip3.twig.ce.service.call

import com.mongodb.client.model.Filters.*
import io.sip3.twig.ce.domain.SessionRequest
import io.sip3.twig.ce.service.SessionService
import org.bson.Document
import org.bson.conversions.Bson
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
open class CallSessionService : SessionService() {

    @Value("\${session.call.termination-timeout}")
    private val terminationTimeout: Long = 10000

    override fun findInRawBySessionRequest(req: SessionRequest): Iterator<Document> {
        requireNotNull(req.createdAt) { "created_at" }
        requireNotNull(req.terminatedAt) { "terminated_at" }
        requireNotNull(req.callId) { "call_id" }

        val filters = mutableListOf<Bson>().apply {
            add(gte("created_at", req.createdAt!! - terminationTimeout))
            add(lte("created_at", req.terminatedAt!! + terminationTimeout))
            add(`in`("call_id", req.callId!!))
        }

        return mongoClient.find("sip_call_raw", Pair(req.createdAt!!, req.terminatedAt!!), and(filters))
    }
}
