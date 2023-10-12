package app.aaps.plugins.sync.nsclient.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.db.BCR
import app.aaps.core.data.db.BS
import app.aaps.core.data.db.CA
import app.aaps.core.data.db.EB
import app.aaps.core.data.db.OE
import app.aaps.core.data.db.TB
import app.aaps.core.data.db.TE
import app.aaps.core.data.db.TT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.main.utils.worker.LoggingWorker
import app.aaps.core.utils.JsonHelper
import app.aaps.core.utils.receivers.DataWorkerStorage

import app.aaps.database.entities.EffectiveProfileSwitch
import app.aaps.database.entities.ProfileSwitch
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.nsclient.extensions.extendedBolusFromJson
import app.aaps.plugins.sync.nsclient.extensions.fromJson
import app.aaps.plugins.sync.nsclient.extensions.isEffectiveProfileSwitch
import app.aaps.plugins.sync.nsclient.extensions.temporaryBasalFromJson
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class NSClientAddUpdateWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var config: Config
    @Inject lateinit var sp: SP
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var storeDataForDb: StoreDataForDb
    @Inject lateinit var profileUtil: ProfileUtil

    override suspend fun doWorkAndLog(): Result {
        val treatments = dataWorkerStorage.pickupJSONArray(inputData.getLong(DataWorkerStorage.STORE_KEY, -1))
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        val ret = Result.success()
        var latestDateInReceivedData = 0L

        for (i in 0 until treatments.length()) {
            var json = treatments.getJSONObject(i)
            aapsLogger.debug(LTag.DATABASE, "Received NS treatment: $json")

            val insulin = JsonHelper.safeGetDouble(json, "insulin")
            val carbs = JsonHelper.safeGetDouble(json, "carbs")
            var eventType = JsonHelper.safeGetString(json, "eventType")
            if (eventType == null) {
                aapsLogger.debug(LTag.NSCLIENT, "Wrong treatment. Ignoring : $json")
                continue
            }

            //Find latest date in treatment
            val mills = JsonHelper.safeGetLong(json, "mills")
            if (mills != 0L && mills < dateUtil.now())
                if (mills > latestDateInReceivedData) latestDateInReceivedData = mills

            if (insulin > 0) {
                if (sp.getBoolean(app.aaps.core.utils.R.string.key_ns_receive_insulin, false) || config.NSCLIENT) {
                    BS.fromJson(json)?.let { bolus ->
                        storeDataForDb.boluses.add(bolus)
                    } ?: aapsLogger.error("Error parsing bolus json $json")
                }
            }
            if (carbs > 0) {
                if (sp.getBoolean(app.aaps.core.utils.R.string.key_ns_receive_carbs, false) || config.NSCLIENT) {
                    CA.fromJson(json)?.let { carb ->
                        storeDataForDb.carbs.add(carb)
                    } ?: aapsLogger.error("Error parsing bolus json $json")
                }
            }
            // Convert back emulated TBR -> EB
            if (eventType == TE.Type.TEMPORARY_BASAL.text && json.has("extendedEmulated")) {
                val ebJson = json.getJSONObject("extendedEmulated")
                ebJson.put("_id", json.getString("_id"))
                ebJson.put("isValid", json.getBoolean("isValid"))
                ebJson.put("mills", mills)
                json = ebJson
                eventType = JsonHelper.safeGetString(json, "eventType")

                activePlugin.activePump.let { if (it is VirtualPump) it.fakeDataDetected = true }
            }
            when {
                insulin > 0 || carbs > 0                                          -> Any()
                eventType == TE.Type.TEMPORARY_TARGET.text                        ->
                    if (sp.getBoolean(app.aaps.core.utils.R.string.key_ns_receive_temp_target, false) || config.NSCLIENT) {
                        TT.fromJson(json, profileUtil)?.let { temporaryTarget ->
                            storeDataForDb.temporaryTargets.add(temporaryTarget)
                        } ?: aapsLogger.error("Error parsing TT json $json")
                    }

                eventType == TE.Type.NOTE.text && json.isEffectiveProfileSwitch() -> // replace this by new Type when available in NS
                    if (sp.getBoolean(app.aaps.core.utils.R.string.key_ns_receive_profile_switch, false) || config.NSCLIENT) {
                        EffectiveProfileSwitch.fromJson(json, dateUtil)?.let { effectiveProfileSwitch ->
                            storeDataForDb.effectiveProfileSwitches.add(effectiveProfileSwitch)
                        } ?: aapsLogger.error("Error parsing EffectiveProfileSwitch json $json")
                    }

                eventType == TE.Type.BOLUS_WIZARD.text                            ->
                    BCR.fromJson(json)?.let { bolusCalculatorResult ->
                        storeDataForDb.bolusCalculatorResults.add(bolusCalculatorResult)
                    } ?: aapsLogger.error("Error parsing BolusCalculatorResult json $json")

                eventType == TE.Type.CANNULA_CHANGE.text ||
                    eventType == TE.Type.INSULIN_CHANGE.text ||
                    eventType == TE.Type.SENSOR_CHANGE.text ||
                    eventType == TE.Type.FINGER_STICK_BG_VALUE.text ||
                    eventType == TE.Type.NONE.text ||
                    eventType == TE.Type.ANNOUNCEMENT.text ||
                    eventType == TE.Type.QUESTION.text ||
                    eventType == TE.Type.EXERCISE.text ||
                    eventType == TE.Type.NOTE.text ||
                    eventType == TE.Type.PUMP_BATTERY_CHANGE.text                 ->
                    if (sp.getBoolean(app.aaps.core.utils.R.string.key_ns_receive_therapy_events, false) || config.NSCLIENT) {
                        TE.fromJson(json)?.let { therapyEvent ->
                            storeDataForDb.therapyEvents.add(therapyEvent)
                        } ?: aapsLogger.error("Error parsing TherapyEvent json $json")
                    }

                eventType == TE.Type.COMBO_BOLUS.text                             ->
                    if (config.isEngineeringMode() && sp.getBoolean(R.string.key_ns_receive_tbr_eb, false) || config.NSCLIENT) {
                        EB.extendedBolusFromJson(json)?.let { extendedBolus ->
                            storeDataForDb.extendedBoluses.add(extendedBolus)
                        } ?: aapsLogger.error("Error parsing ExtendedBolus json $json")
                    }

                eventType == TE.Type.TEMPORARY_BASAL.text                         ->
                    if (config.isEngineeringMode() && sp.getBoolean(R.string.key_ns_receive_tbr_eb, false) || config.NSCLIENT) {
                        TB.temporaryBasalFromJson(json)?.let { temporaryBasal ->
                            storeDataForDb.temporaryBasals.add(temporaryBasal)
                        } ?: aapsLogger.error("Error parsing TemporaryBasal json $json")
                    }

                eventType == TE.Type.PROFILE_SWITCH.text                          ->
                    if (sp.getBoolean(app.aaps.core.utils.R.string.key_ns_receive_profile_switch, false) || config.NSCLIENT) {
                        ProfileSwitch.fromJson(json, dateUtil, activePlugin)?.let { profileSwitch ->
                            storeDataForDb.profileSwitches.add(profileSwitch)
                        } ?: aapsLogger.error("Error parsing ProfileSwitch json $json")
                    }

                eventType == TE.Type.APS_OFFLINE.text                             ->
                    if (sp.getBoolean(app.aaps.core.utils.R.string.key_ns_receive_offline_event, false) && config.isEngineeringMode() || config.NSCLIENT) {
                        OE.fromJson(json)?.let { offlineEvent ->
                            storeDataForDb.offlineEvents.add(offlineEvent)
                        } ?: aapsLogger.error("Error parsing OfflineEvent json $json")
                    }
            }
        }
        storeDataForDb.storeTreatmentsToDb()
        activePlugin.activeNsClient?.updateLatestTreatmentReceivedIfNewer(latestDateInReceivedData)
        return ret
    }
}