package ru.protolink.communicator.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.protolink.communicator.data.SystemEntities
import ru.protolink.communicator.data.api.AddEntityRequest
import ru.protolink.communicator.data.api.EntityDto
import ru.protolink.communicator.data.api.GetEntitiesRequest
import ru.protolink.communicator.data.api.ProtoLinkApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared UC bag list / pick / stop-spawn for Messenger (Win UserContactsBagClient parity).
 * Page-all + enrich counts; StartupFast escalates when newest-first first page is full.
 */
object UserContactsBagClient {

    suspend fun ensureBagIdStartupFast(api: ProtoLinkApi, ownerUserId: String): String? {
        val first = listFirstPageBags(api, ownerUserId)
        if (UserContactsBagHelpers.shouldCreateBag(first)) {
            val pickedEmpty = tryPickEnriched(api, ownerUserId, earlyExitOnPartner = true)
            if (!pickedEmpty.isNullOrBlank()) return pickedEmpty
            return createBag(api, ownerUserId)
        }

        // Truncated newest-first page can hide older partner bags — page-all + early-exit enrich.
        if (!UserContactsBagHelpers.canPickFromStartupFastFirstPage(first)) {
            return tryPickEnriched(
                api,
                ownerUserId,
                earlyExitOnPartner = true,
                seedFirstPage = first
            )
        }

        val snapshots = UserContactsBagHelpers.toSnapshots(first).toMutableList()
        enrichPersonRowCounts(api, snapshots, earlyExitOnPartner = true)
        return UserContactsBagHelpers.pickBagId(snapshots)
    }

    /**
     * Full enrich pick (ContinueContacts richest-bag reconcile). Create only when no bags.
     */
    suspend fun ensureBagId(api: ProtoLinkApi, ownerUserId: String): String? {
        var picked = tryPickEnriched(api, ownerUserId, earlyExitOnPartner = true)
        if (!picked.isNullOrBlank()) return picked

        picked = tryPickEnriched(api, ownerUserId, earlyExitOnPartner = true)
        if (!picked.isNullOrBlank()) return picked

        return createBag(api, ownerUserId)
    }

    suspend fun listFirstPageBags(api: ProtoLinkApi, ownerUserId: String): List<EntityDto> =
        fetchListPage(api, ownerUserId, skip = 0)

    suspend fun listAllBags(
        api: ProtoLinkApi,
        ownerUserId: String,
        seedFirstPage: List<EntityDto>? = null
    ): List<EntityDto> {
        var bags: List<EntityDto> = emptyList()
        var pagesDone = 0
        val skipAfterFirst: Int

        if (seedFirstPage != null) {
            bags = UserContactsBagHelpers.mergeBagPages(bags, seedFirstPage)
            pagesDone++
            if (!UserContactsBagHelpers.isListPageFull(seedFirstPage)) return bags
            skipAfterFirst = UserContactsBagHelpers.ListPageSize
        } else {
            val first = fetchListPage(api, ownerUserId, skip = 0)
            bags = UserContactsBagHelpers.mergeBagPages(bags, first)
            pagesDone++
            if (!UserContactsBagHelpers.isListPageFull(first)) return bags
            skipAfterFirst = UserContactsBagHelpers.ListPageSize
        }
        var skip = skipAfterFirst

        while (pagesDone < UserContactsBagHelpers.ListMaxPages) {
            val remaining = UserContactsBagHelpers.ListMaxPages - pagesDone
            val waveSize = minOf(UserContactsBagHelpers.ListPageParallelism, remaining)
            val waveSkips = IntArray(waveSize) { i -> skip + i * UserContactsBagHelpers.ListPageSize }
            val wavePages = coroutineScope {
                waveSkips.map { s -> async { fetchListPage(api, ownerUserId, s) } }.awaitAll()
            }

            var hitShort = false
            for (page in wavePages) {
                bags = UserContactsBagHelpers.mergeBagPages(bags, page)
                pagesDone++
                if (!UserContactsBagHelpers.isListPageFull(page)) {
                    hitShort = true
                    break
                }
            }
            if (hitShort) break
            skip += waveSize * UserContactsBagHelpers.ListPageSize
        }
        return bags
    }

    /**
     * When 2+ bags, count kids so pick prefers the richest (Tina/person rows).
     * Oldest-first; early-exit after first partner-bearing bag.
     */
    suspend fun enrichPersonRowCounts(
        api: ProtoLinkApi,
        bags: MutableList<UserContactsBagHelpers.BagSnapshot>,
        earlyExitOnPartner: Boolean = false
    ) {
        if (bags.size < 2) return

        val ordered = UserContactsBagHelpers.orderBagsForEnrich(bags)
        val gate = Semaphore(8)
        val stop = AtomicBoolean(false)

        coroutineScope {
            ordered.map { bag ->
                async {
                    if (stop.get()) return@async
                    try {
                        gate.withPermit {
                            if (stop.get()) return@withPermit
                            val kids = runCatching {
                                api.getEntities(
                                    GetEntitiesRequest(
                                        parentIds = listOf(bag.id),
                                        skip = 0,
                                        take = UserContactsBagHelpers.KidsTake,
                                        includeValues = false
                                    )
                                )
                            }.getOrNull()
                            bag.personRowCount = kids?.size ?: 0
                            if (earlyExitOnPartner &&
                                (bag.personRowCount ?: 0) >= UserContactsBagHelpers.PartnerRowThreshold
                            ) {
                                stop.set(true)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        bag.personRowCount = 0
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun tryPickEnriched(
        api: ProtoLinkApi,
        ownerUserId: String,
        earlyExitOnPartner: Boolean,
        seedFirstPage: List<EntityDto>? = null
    ): String? {
        val entities = listAllBags(api, ownerUserId, seedFirstPage)
        if (UserContactsBagHelpers.shouldCreateBag(entities)) return null

        val snapshots = UserContactsBagHelpers.toSnapshots(entities).toMutableList()
        enrichPersonRowCounts(api, snapshots, earlyExitOnPartner)
        return UserContactsBagHelpers.pickBagId(snapshots)
    }

    private suspend fun fetchListPage(
        api: ProtoLinkApi,
        ownerUserId: String,
        skip: Int
    ): List<EntityDto> =
        api.getEntities(
            GetEntitiesRequest(
                parentIds = listOf(SystemEntities.CONTACTS, ownerUserId),
                includeValues = false,
                take = UserContactsBagHelpers.ListPageSize,
                skip = skip
            )
        )

    private suspend fun createBag(api: ProtoLinkApi, ownerUserId: String): String? {
        val created = api.addEntity(
            AddEntityRequest(
                code = "UserContacts",
                parentIds = listOf(SystemEntities.CONTACTS, ownerUserId)
            )
        )
        return created.id?.takeIf { it.isNotBlank() }
    }
}
