package io.github.mymoney

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import androidx.room.withTransaction
import io.github.mymoney.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Explicit, marker-scoped physical-device UI fixtures. No default app database mutations. */
class AssetUiFixtureInstrumentation : Instrumentation() {
    private var args = Bundle()
    private val report = linkedMapOf<String, Any?>()
    private val names = listOf("QA_ASSET_CASH", "QA_ASSET_WALLET", "QA_ASSET_CREDIT")
    private val kinds = listOf("CASH", "PAYMENT", "CREDIT")
    private val positions = listOf(100_000L, 20_000L, -30_000L)
    private val markerName = "mymoney-asset-ui-fixture.json"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        var failure: Throwable? = null
        val mode = args.getString("mode") ?: "refuse"
        report["mode"] = mode
        report["device"] = "${Build.MANUFACTURER}/${Build.MODEL}"
        try {
            require(mode in setOf("seed", "cleanup")) { "Explicit mode=seed or mode=cleanup required; no changes made" }
            requirePhysicalDevice()
            runBlocking(Dispatchers.IO) {
                withTimeout(60_000) {
                    AssetStore.init(targetContext)
                    AssetStore.awaitReady()
                    val db = AppDb.get(targetContext)
                    if (mode == "seed") seed(db) else cleanup(db)
                    AssetStore.refresh()
                }
            }
        } catch (error: Throwable) { failure = error }
        report["status"] = if (failure == null) "passed" else "failed"
        report["error"] = failure?.stackTraceToString()
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("status", report["status"] as String)
            putString("report", MiniJson.encode(report))
            putString("error", failure?.stackTraceToString())
        })
    }

    private fun requirePhysicalDevice() {
        val emulator = Build.FINGERPRINT.contains("generic", true) || Build.FINGERPRINT.contains("emulator", true) ||
            Build.MODEL.contains("sdk", true) || Build.MODEL.contains("emulator", true) ||
            Build.PRODUCT.contains("sdk", true) || Build.HARDWARE.contains("goldfish", true) || Build.HARDWARE.contains("ranchu", true)
        require(!emulator) { "This explicitly authorized QA fixture is restricted to the physical device" }
    }

    private suspend fun seed(db: AppDb) {
        val marker = File(targetContext.filesDir, markerName)
        require(!marker.exists()) { "Fixture marker exists; cleanup its exact UUIDs first" }
        val now = System.currentTimeMillis()
        val rows = names.mapIndexed { index, name -> AssetAccount(
            id = UUID.randomUUID().toString(), name = name, kind = kinds[index], sortOrder = index,
            initialized = true, createdAt = now, updatedAt = now,
        ) }
        // Persist identity before the transaction; a crash never leaves unidentified fixture accounts.
        require(marker.createNewFile()) { "Fixture marker already exists" }
        val markerText = MiniJson.encode(linkedMapOf(
            "kind" to "MyMoneyAssetUiFixture", "format" to 1, "createdAt" to now,
            "accounts" to rows.map { linkedMapOf("id" to it.id, "name" to it.name, "kind" to it.kind) },
        ))
        FileOutputStream(marker).use { output -> output.write(markerText.toByteArray(Charsets.UTF_8)); output.fd.sync() }
        db.withTransaction {
            val original = db.assets().accounts()
            val allNames = original.flatMap { listOf(it.name) + AssetMath.aliases(it) }
            require(names.none { it in allNames }) { "A fixture name already belongs to an account or alias" }
            require(db.bills().allOnce().none { it.account in names } &&
                db.recognitions().allOnce().none { it.account in names }) { "A fixture name already appears in real records" }
            AssetMath.validate(AssetSnapshot(original + rows, db.assets().entries()))
            rows.forEachIndexed { index, row ->
                db.assets().upsertAccount(row)
                db.assets().insertEntry(AssetEntry(id = UUID.randomUUID().toString(),
                    operationId = "qa-fixture-opening-${row.id}", accountId = row.id,
                    deltaCents = positions[index], kind = "OPENING", note = "MyMoney资产UI验证夹具",
                    createdAt = maxOf(now + index, (db.assets().latestEntryTime() ?: 0L) + 1)))
            }
            AssetMath.validate(AssetSnapshot(db.assets().accounts(), db.assets().entries()))
        }
        report["fixtureGeneration"] = "DAO transaction only; not evidence of UI account creation"
        report["createdAccounts"] = rows.size
        report["createdEntries"] = rows.size
        report["marker"] = marker.absolutePath
        report["accounts"] = rows.mapIndexed { index, row -> mapOf("id" to row.id, "name" to row.name,
            "kind" to row.kind, "positionCents" to positions[index]) }
    }

    private suspend fun cleanup(db: AppDb) {
        val marker = File(targetContext.filesDir, markerName)
        require(marker.isFile) { "Cleanup requires the existing fixture marker; no changes made" }
        val root = MiniJson.decode(marker.readText()) as? Map<*, *> ?: error("Fixture marker is not an object")
        require(root["kind"] == "MyMoneyAssetUiFixture" && root["format"] == 1L) { "Fixture marker identity mismatch" }
        val raw = root["accounts"] as? List<*> ?: error("Fixture marker account list missing")
        require(raw.size == 3) { "Fixture marker must contain exactly three account UUIDs" }
        val ids = raw.mapIndexed { index, value ->
            val row = value as? Map<*, *> ?: error("Invalid fixture account marker")
            require(row["name"] == names[index] && row["kind"] == kinds[index]) { "Fixture marker account identity mismatch" }
            val id = row["id"] as? String ?: error("Fixture UUID missing")
            require(UUID.fromString(id).toString() == id) { "Fixture UUID invalid" }
            id
        }.toSet()
        require(ids.size == 3) { "Fixture UUIDs are not unique" }
        var billCount = 0
        var entryCount = 0
        var accountCount = 0
        db.withTransaction {
            val allAccounts = db.assets().accounts()
            val fixtureAccounts = allAccounts.filter { it.id in ids }
            // A UI test may rename a QA account. UUID remains authoritative, and no other account may adopt its name.
            require(fixtureAccounts.all { it.createdAt == (root["createdAt"] as? Long) }) { "Fixture account creation identity changed" }
            require(allAccounts.none { it.id !in ids && (it.name in names || AssetMath.aliases(it).any { alias -> alias in names }) }) {
                "A non-fixture account now depends on a fixture name; cleanup refused"
            }
            val fixtureNames = names.toSet() + fixtureAccounts.map { it.name } + fixtureAccounts.flatMap(AssetMath::aliases)
            val allBills = db.bills().allOnce()
            val related = allBills.filter { it.accountId in ids || it.transferAccountId in ids || it.account in fixtureNames }
            require(related.all { it.sourcePkg.startsWith("io.github.mymoney.manual.") && it.accountId in ids &&
                (it.transferAccountId == null || it.transferAccountId in ids) && it.recognitionId == null && it.assetKey.isNotBlank() }) {
                "A real or cross-account bill depends on fixture accounts; cleanup refused"
            }
            val keys = related.map { it.assetKey }.toSet()
            require(allBills.none { it !in related && (it.assetKey in keys || it.feeParentKey in keys) }) { "A non-fixture bill references fixture business identity" }
            require(related.all { it.feeParentKey == null || it.feeParentKey in keys }) { "A fixture fee references a non-fixture transfer" }
            val billIds = related.map { it.id }.toSet()
            require(db.recognitions().allOnce().none { it.billId in billIds || it.account in fixtureNames }) {
                "Recognition history references fixture data; cleanup refused to protect history"
            }
            val allEntries = db.assets().entries()
            val fixtureEntries = allEntries.filter { it.accountId in ids }
            val entryIds = fixtureEntries.map { it.id }.toSet()
            val operations = fixtureEntries.map { it.operationId }.toSet()
            val entryBillKeys = fixtureEntries.mapNotNull { it.billKey }.toSet()
            require(allBills.none { it !in related && it.assetKey in entryBillKeys }) { "Fixture postings refer to a non-fixture bill" }
            require(allEntries.none { it.accountId !in ids &&
                (it.operationId in operations || it.billKey in entryBillKeys || it.reversalOf in entryIds) }) {
                "Asset postings outside fixture UUIDs depend on the fixture; cleanup refused"
            }
            require(fixtureEntries.all { it.reversalOf == null || it.reversalOf in entryIds }) {
                "Fixture reversal references a non-fixture posting; cleanup refused"
            }
            require(related.all { row -> allEntries.filter { it.billKey == row.assetKey }.all { it.accountId in ids } }) {
                "Fixture bill references a real account posting; cleanup refused"
            }
            // Scope all writes by previously validated UUIDs; never clear a whole table.
            related.forEach { db.bills().delete(it.id) }
            ids.forEach { id ->
                db.openHelper.writableDatabase.execSQL("DELETE FROM asset_entries WHERE accountId = ?", arrayOf(id))
                db.assets().deleteAccount(id)
            }
            AssetMath.validate(AssetSnapshot(db.assets().accounts(), db.assets().entries()))
            billCount = related.size; entryCount = fixtureEntries.size; accountCount = fixtureAccounts.size
        }
        // Marker removal happens only after a fully committed, validated cleanup transaction.
        require(marker.delete()) { "Data cleaned but marker deletion failed; marker retained for a safe retry" }
        report["deletedAccounts"] = accountCount
        report["deletedEntries"] = entryCount
        report["deletedBills"] = billCount
        report["deletedRecognitions"] = 0
        report["markerRemoved"] = true
    }
}
