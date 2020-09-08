package io.sip3.twig.ce.service.media

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import io.sip3.twig.ce.domain.SessionRequest
import io.sip3.twig.ce.mongo.MongoClient
import io.sip3.twig.ce.service.media.domain.LegSession
import io.sip3.twig.ce.service.media.domain.MediaStatistic
import io.sip3.twig.ce.service.media.util.LegSessionUtil.createLegSession
import io.sip3.twig.ce.service.media.util.LegSessionUtil.generateLegId
import io.sip3.twig.ce.service.media.util.LegSessionUtil.generatePartyId
import io.sip3.twig.ce.service.media.util.MediaStatisticUtil.createMediaStatistic
import io.sip3.twig.ce.service.media.util.MediaStatisticUtil.updateMediaStatistic
import io.sip3.twig.ce.service.media.util.ReportUtil.splitReport
import org.bson.Document
import org.bson.conversions.Bson
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class MediaSessionService {

    @Value("\${session.media.block-count}")
    private var blockCount: Int = 28

    @Value("\${session.media.termination-timeout}")
    private var terminationTimeout: Long = 60000

    @Autowired
    private lateinit var mongoClient: MongoClient

    fun details(req: SessionRequest): Any? {
        requireNotNull(req.createdAt, { "created_at" })
        requireNotNull(req.terminatedAt, { "terminated_at" })
        requireNotNull(req.callId, { "call_id" })

        val rtp = findLegSessions("rtp", req.createdAt!!, req.terminatedAt!!, req.callId!!)
        val rtcp = findLegSessions("rtcp", req.createdAt!!, req.terminatedAt!!, req.callId!!)

        return rtp.keys.plus(rtcp.keys)
                .map { mapOf("rtp" to rtp[it], "rtcp" to rtcp[it]) }
    }

    private fun findLegSessions(source: String, createdAt: Long, terminatedAt: Long, callId: List<String>, withBlocks: Boolean = true): Map<String, LegSession> {
        val sessions = mutableMapOf<String, LegSession>()
        val reports = mutableListOf<Document>()

        // Create leg sessions from reports index
        find("rtpr_${source}_index", createdAt, terminatedAt, callId)
                .asSequence()
                .groupBy { generateLegId(it) }
                .forEach { (legId, documents) ->
                    sessions[legId] = createLegSession(documents, blockCount)
                }

        // Add blocks to media sessions
        sessions.forEach { (legId, legSession) ->
            // Find Raw reports
            var legReports = reports.filter { generateLegId(it) == legId }

            if (legReports.isEmpty()) {
                find("rtpr_${source}_raw", legSession.createdAt, legSession.terminatedAt, listOf(legSession.callId))
                        .asSequence()
                        .forEach {
                            reports.add(it)
                        }
                legReports = reports.filter { generateLegId(it) == legId }
            }

            if (withBlocks) {
                // Update Media Session leg session party
                legReports
                        .groupBy { generatePartyId(it) }
                        .forEach { (_, reports) -> updateMediaSession(legSession, reports) }
            }
        }

        return sessions
    }

    // TODO: Hardcoded mediaSession selection.
    private fun updateMediaSession(legSession: LegSession, reports: List<Document>) {
        val firstReport = reports.first()
        val mediaSession = if (((firstReport.getInteger("src_port") - legSession.srcPort) in 0..1)
                && ((firstReport.getInteger("dst_port") - legSession.dstPort) in 0..1)) {
            legSession.out.first()
        } else {
            legSession.`in`.first()
        }

        if (mediaSession.duration == 0) {
            return
        }

        val blocks = ArrayList<MediaStatistic>(blockCount)
        val blockDuration = legSession.duration / blockCount

        var remainingDuration: Int
        var currentBlock = MediaStatistic()

        if (mediaSession.createdAt == legSession.createdAt) {
            remainingDuration = blockDuration
        } else {
            val startDiff = (mediaSession.createdAt - legSession.createdAt).toInt()
            repeat(startDiff / blockDuration) {
                blocks.add(MediaStatistic())
            }
            remainingDuration = blockDuration - (startDiff % blockDuration)
        }

        reports.forEach { report ->
            val reportDuration = report.getInteger("duration")
            when {
                reportDuration < remainingDuration -> {
                    updateMediaStatistic(currentBlock, report)
                    remainingDuration -= reportDuration
                }

                reportDuration > remainingDuration -> {
                    val chunks = splitReport(report, remainingDuration, blockDuration)
                    val iterator = chunks.iterator()

                    updateMediaStatistic(currentBlock, iterator.next())
                    while (iterator.hasNext()) {
                        blocks.add(currentBlock)
                        currentBlock = createMediaStatistic(iterator.next())
                    }

                    remainingDuration = blockDuration - chunks.last().getInteger("duration")
                }

                reportDuration == remainingDuration -> {
                    updateMediaStatistic(currentBlock, report)
                    blocks.add(currentBlock)
                    currentBlock = MediaStatistic()
                    remainingDuration = blockDuration
                }
            }
        }

        if (currentBlock.packets.expected != 0 && blocks.size < blockCount) {
            blocks.add(currentBlock)
        }

        while (blocks.size < blockCount) {
            blocks.add(MediaStatistic())
        }

        mediaSession.blocks.addAll(blocks)
    }

    private fun find(prefix: String, createdAt: Long, terminatedAt: Long, callId: List<String>): Iterator<Document> {
        val filters = mutableListOf<Bson>().apply {
            add(Filters.gte("started_at", createdAt))
            add(Filters.lte("started_at", terminatedAt + terminationTimeout))
            add(Filters.`in`("call_id", callId))
        }

        return mongoClient.find(prefix, Pair(createdAt, terminatedAt + terminationTimeout),
                Filters.and(filters), Sorts.ascending("started_at"))
    }
}