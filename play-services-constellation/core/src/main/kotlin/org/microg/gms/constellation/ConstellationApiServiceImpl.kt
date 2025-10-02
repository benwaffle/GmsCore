/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation

import androidx.lifecycle.lifecycleScope
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.RemoteException
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.libraries.entitlement.ServiceEntitlementException
import com.android.libraries.entitlement.Ts43Authentication
import com.android.libraries.entitlement.Ts43Operation
import com.android.libraries.entitlement.odsa.AcquireTemporaryTokenOperation.AcquireTemporaryTokenRequest
import com.android.libraries.entitlement.utils.Ts43Constants
import com.google.android.gms.common.BuildConfig
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.Status
import com.google.android.gms.constellation.PhoneNumberVerification
import com.google.android.gms.constellation.VerifyPhoneNumberRequest
import com.google.android.gms.constellation.VerifyPhoneNumberResponse
import com.google.android.gms.constellation.internal.IConstellationApiService
import com.google.android.gms.constellation.internal.IConstellationCallbacks
import com.google.android.gms.droidguard.DroidGuardClient
import com.google.android.gms.iid.InstanceID
import com.google.android.gms.tasks.await
import com.google.common.collect.ImmutableList
import com.squareup.wire.GrpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.microg.gms.common.PackageUtils
import org.microg.gms.droidguard.core.VersionUtil
import org.microg.gms.gcm.GcmConstants
import org.microg.gms.phonenumberverification.ChallengePreference
import org.microg.gms.phonenumberverification.ChallengeResponse
import org.microg.gms.phonenumberverification.ClientInfo
import org.microg.gms.phonenumberverification.CountryInfo
import org.microg.gms.phonenumberverification.DeviceId
import org.microg.gms.phonenumberverification.DeviceSignals
import org.microg.gms.phonenumberverification.IMSIRequest
import org.microg.gms.phonenumberverification.IdTokenRequest
import org.microg.gms.phonenumberverification.MTChallengePreference
import org.microg.gms.phonenumberverification.MobileOperatorInfo
import org.microg.gms.phonenumberverification.Param
import org.microg.gms.phonenumberverification.PhoneDeviceVerificationClient
import org.microg.gms.phonenumberverification.PhoneNumberSource
import org.microg.gms.phonenumberverification.ProceedRequest
import org.microg.gms.phonenumberverification.RequestHeader
import org.microg.gms.phonenumberverification.RequestTrigger
import org.microg.gms.phonenumberverification.RoamingState
import org.microg.gms.phonenumberverification.SIMAssociation
import org.microg.gms.phonenumberverification.SIMInfo
import org.microg.gms.phonenumberverification.SIMSlot
import org.microg.gms.phonenumberverification.SIMState
import org.microg.gms.phonenumberverification.ServerChallengeResponse
import org.microg.gms.phonenumberverification.ServiceState
import org.microg.gms.phonenumberverification.StructuredAPIParams
import org.microg.gms.phonenumberverification.SyncRequest
import org.microg.gms.phonenumberverification.TelephonyInfo
import org.microg.gms.phonenumberverification.TelephonyPhoneNumber
import org.microg.gms.phonenumberverification.TriggerType
import org.microg.gms.phonenumberverification.Ts43ChallengeResponse
import org.microg.gms.phonenumberverification.Verification
import org.microg.gms.phonenumberverification.VerificationAssociation
import org.microg.gms.phonenumberverification.VerificationState
import java.net.URL
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Locale
import java.util.UUID
import kotlin.text.toString

class ConstellationApiServiceImpl(private val context: Context) : IConstellationApiService.Stub() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val versionUtil = VersionUtil(context)

    companion object {
        private const val TAG = "ConstellationApi"
        private const val UPI_CARRIER_TOS_TS43 = "upi-carrier-tos-ts43"
        private const val PHONE_VERIFICATION_BASE_URL = "https://phonedeviceverification-pa.googleapis.com"
    }

    @Throws(RemoteException::class)
    override fun verifyPhoneNumber(
        callbacks: IConstellationCallbacks,
        request: VerifyPhoneNumberRequest,
        apiMetadata: ApiMetadata
    ) {
        Log.d(TAG, "verifyPhoneNumber called: request=$request, apiMetadata=$apiMetadata")

        try {
            if (UPI_CARRIER_TOS_TS43 == request.upiPolicyId) {
                Log.d(TAG, "Processing UPI carrier TOS TS43 verification")
                // TODO: do we do TS43 on every call, or cache?
                scope.launch {
                    handleTs43Verification(callbacks, request, apiMetadata)
                }
            } else {
                Log.d(TAG, "Unknown verification for policy: ${request.upiPolicyId}")
                // Default response for non-TS43 verifications
                val pnv = PhoneNumberVerification().apply {
                    verificationStatus = 9
                }

                val response = VerifyPhoneNumberResponse().apply {
                    verifications = arrayOf(pnv)
                }
                Log.d(TAG, "replying with $response")
                callbacks.onPhoneNumberVerificationsCompleted(Status.SUCCESS, response, apiMetadata)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in verifyPhoneNumber", e)
            callbacks.onPhoneNumberVerificationsCompleted(
                Status.INTERNAL_ERROR,
                VerifyPhoneNumberResponse(),
                apiMetadata
            )
        }
    }

    @Throws(Exception::class)
    private suspend fun handleTs43Verification(
        callbacks: IConstellationCallbacks,
        request: VerifyPhoneNumberRequest,
        apiMetadata: ApiMetadata
    ) {
        val sessionId = UUID.randomUUID().toString()

        try {
            // Step 1: Send SyncRequest
            Log.d(TAG, "Sending SyncRequest via gRPC")
            val syncRequest = createSyncRequest(sessionId)
            val phoneVerificationClient = createGrpcClient()

            val syncResponse = withContext(Dispatchers.IO) {
                phoneVerificationClient.Sync().execute(syncRequest)
            }
            Log.d(TAG, "SyncRequest sent: $syncRequest")
            Log.d(TAG, "SyncResponse received: $syncResponse")

            if (syncResponse.responses[0].verification?.state != VerificationState.VERIFICATION_STATE_PENDING) {
                throw Exception("Unexpected verification state: ${syncResponse.responses[0].verification?.state}")
            }

            // Step 2: Perform TS43 auth flow
            Log.d(TAG, "Starting TS43 authentication flow")
            val temporaryToken = performTs43AuthFlow()

            // Step 3: Send ProceedRequest
            Log.d(TAG, "Sending ProceedRequest via gRPC")
            val proceedRequest = createProceedRequest(temporaryToken, sessionId)

            val proceedResponse = withContext(Dispatchers.IO) {
                phoneVerificationClient.Proceed().execute(proceedRequest)
            }
            Log.d(TAG, "ProceedRequest sent: $proceedRequest")
            Log.d(TAG, "ProceedResponse received: $proceedResponse")

            // Create successful response
            val pnv = PhoneNumberVerification().apply {
                verificationStatus = 1 // Verified status
            }

            val response = VerifyPhoneNumberResponse().apply {
                verifications = arrayOf(pnv)
            }

            callbacks.onPhoneNumberVerificationsCompleted(Status.SUCCESS, response, apiMetadata)
        } catch (e: Exception) {
            Log.e(TAG, "Error in TS43 verification", e)
            callbacks.onPhoneNumberVerificationsCompleted(
                Status.INTERNAL_ERROR,
                VerifyPhoneNumberResponse(),
                apiMetadata
            )
        }
    }

    @SuppressLint("HardwareIds")
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun createSyncRequest(sessionId: String): SyncRequest {
        val subs = subscriptionManager.activeSubscriptionInfoList

        return SyncRequest.build {
            verifications(
                subs?.map { sub ->
                    val tm = telephonyManager.createForSubscriptionId(sub.subscriptionId)

                    Verification.build {
                        state = VerificationState.VERIFICATION_STATE_NONE
                        association = VerificationAssociation.build {
                            sim = createSimAssociation(sub, tm)
                        }
                        telephony_info = createTelephonyInfo(sub, tm)
                        api_params = mapOf(
                            "consent_type" to "RCS_DEFAULT_ON_LEGAL_FYI_IN_SETTINGS",
                            "one_time_verification" to "True",
                            "calling_api" to "verifyPhoneNumber",
                            "required_consumer_consent" to "RCS",
//                                "sessionId" to "<uuid>" // TODO: generate a uuid?
                        ).map { (key, value) ->
                            Param.build {
                                name = key
                                value_ = value
                            }
                        }
                        challenge_preference = ChallengePreference.build {
                            mt_preference = MTChallengePreference.build {
//                                localized_message_template("asdf") // can we ignore if not doing MT SMS challenge?
                            }
                        }
                        structured_api_params = StructuredAPIParams.build {
                            policy_id = UPI_CARRIER_TOS_TS43
                            id_token_request = IdTokenRequest.build {
                                val caller = PackageUtils.getCallingPackage(context)
                                val certHash = PackageUtils.firstSignatureDigestBytes(context, caller)
                                val b64 = Base64.encodeToString(certHash, Base64.DEFAULT)
                                certificate_hash = b64

                                val nonce = ByteArray(32)
                                SecureRandom().nextBytes(nonce)
                                val hex = nonce.joinToString("") { "%02x".format(it) }
                                token_nonce = hex
                            }
                            PackageUtils.getCallingPackage(context)?.let { packageName ->
                                calling_package = packageName
                            }
                            imsi_requests = listOf(IMSIRequest.build {
                                imsi = tm.subscriberId
                            })
                        }
                    }
                } ?: listOf()
            )
            header_(createRequestHeader(sessionId))
        }
    }

    @SuppressLint("HardwareIds")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun createSimAssociation(sub: SubscriptionInfo, tm: TelephonyManager): SIMAssociation {
        val subId = sub.subscriptionId

        return SIMAssociation.build {
            sim_info = SIMInfo.build {
                imsi = listOf(tm.subscriberId)
                sim_readable_number = subscriptionManager.getPhoneNumber(subId)
                telephony_phone_number = listOf(
                    SubscriptionManager.PHONE_NUMBER_SOURCE_UICC,
                    SubscriptionManager.PHONE_NUMBER_SOURCE_IMS,
                    SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER,
                )
                    .map { source -> source to subscriptionManager.getPhoneNumber(subId, source) }
                    .filter { it.second.isNotEmpty() }
                    .map { (source, number) ->
                        TelephonyPhoneNumber.build {
                            this.number = number
                            this.source = when (source) {
                                SubscriptionManager.PHONE_NUMBER_SOURCE_UICC -> PhoneNumberSource.PHONE_NUMBER_SOURCE_IUCC
                                SubscriptionManager.PHONE_NUMBER_SOURCE_IMS -> PhoneNumberSource.PHONE_NUMBER_SOURCE_IMS
                                SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER -> PhoneNumberSource.PHONE_NUMBER_SOURCE_CARRIER
                                else -> PhoneNumberSource.PHONE_NUMBER_SOURCE_UNKNOWN
                            }
                        }
                    }
                iccid = sub.iccId
            }
            sim_slot = SIMSlot.build {
                sub_id = subId
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun createTelephonyInfo(sub: SubscriptionInfo, tm: TelephonyManager): TelephonyInfo {
        return TelephonyInfo.build {
            sim_state = SIMState.SIM_NOT_READY // just based on what I saw from GMS
            group_id_level1 = tm.groupIdLevel1
            sim_operator = MobileOperatorInfo.build {
                country_code = tm.simCountryIso
                mcc_mnc = tm.simOperator
                operator_name = tm.simOperatorName
            }
            network_operator = MobileOperatorInfo.build {
                country_code = tm.networkCountryIso
                mcc_mnc = tm.networkOperator
                operator_name = tm.networkOperatorName
            }
            network_roaming = when (tm.isNetworkRoaming) {
                true -> RoamingState.ROAMING_STATE_ROAMING
                false -> RoamingState.ROAMING_STATE_NOT_ROAMING
            }
            data_roaming = when (tm.isDataRoamingEnabled) {
                true -> RoamingState.ROAMING_STATE_ROAMING
                false -> RoamingState.ROAMING_STATE_NOT_ROAMING
            }
//            sms_capability = when (tm.isDeviceSmsCapable) {
//                true -> SMSCapability.SMS_CAPABILITY_CAPABLE
//                false -> SMSCapability.SMS_CAPABILITY_INCAPABLE
//            }
//            carrier_id_capability
//            premium_sms_permission
            subscription_count = subscriptionManager.activeSubscriptionInfoCount
            subscription_count_max = subscriptionManager.activeSubscriptionInfoCountMax
            sim_index = sub.simSlotIndex
            imei = telephonyManager.getImei(sub.simSlotIndex)
            service_state = when (tm.serviceState?.state) {
                android.telephony.ServiceState.STATE_IN_SERVICE -> ServiceState.SERVICE_STATE_IN_SERVICE
                android.telephony.ServiceState.STATE_OUT_OF_SERVICE -> ServiceState.SERVICE_STATE_OUT_OF_SERVICE
                android.telephony.ServiceState.STATE_EMERGENCY_ONLY -> ServiceState.SERVICE_STATE_EMERGENCY_ONLY
                android.telephony.ServiceState.STATE_POWER_OFF -> ServiceState.SERVICE_STATE_POWER_OFF
                else -> ServiceState.SERVICE_STATE_UNKNOWN
            }
//            service_state_events
            sim_carrier_id = tm.simCarrierId
        }
    }

    @Throws(ServiceEntitlementException::class)
    private fun performTs43AuthFlow(): String {
        try {
            // Get default SIM slot
            val tm = context.getSystemService(TelephonyManager::class.java)
            val slotIndex = 0 // Default to first slot

            // Create TS43 authentication
            val entitlementUrl = URL("https://entitlement-server.example.com") // This should be carrier-specific
            val ts43Auth = Ts43Authentication(context, entitlementUrl, "9.0")

            // Get auth token
            val authToken = ts43Auth.getAuthToken(
                slotIndex,
                Ts43Constants.APP_PHONE_NUMBER_INFORMATION,
                context.packageName,
                "1.0"
            )

            // Create TS43 operation
            val ts43Operation = Ts43Operation(
                context,
                slotIndex,
                entitlementUrl,
                "9.0",
                authToken.token(),
                Ts43Operation.TOKEN_TYPE_NORMAL,
                context.packageName
            )

            // Acquire temporary token for GetSubscriberInfo operation
            val tokenRequest = AcquireTemporaryTokenRequest.builder()
                .setAppId(Ts43Constants.APP_PHONE_NUMBER_INFORMATION)
                .setOperationTargets(ImmutableList.of("GetSubscriberInfo"))
                .build()

            val tokenResponse = ts43Operation.acquireTemporaryToken(tokenRequest)

            Log.d(TAG, "Acquired temporary token for TS43 operation")
            return tokenResponse.temporaryToken()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform TS43 auth flow", e)
            throw ServiceEntitlementException(
                ServiceEntitlementException.ERROR_TOKEN_NOT_AVAILABLE,
                "TS43 auth failed",
                e
            )
        }
    }

    private fun createProceedRequest(temporaryToken: String, sessionId: String): ProceedRequest {
        val builder = ProceedRequest.Builder()

        // Create verification in pending state
        val verificationBuilder = Verification.Builder()
        verificationBuilder.state(VerificationState.VERIFICATION_STATE_PENDING)

        // Create TS43 challenge response with temporary token
        val ts43ResponseBuilder = Ts43ChallengeResponse.Builder()

        val serverResponseBuilder = ServerChallengeResponse.Builder()
        serverResponseBuilder.temporary_token(temporaryToken)
        ts43ResponseBuilder.server_challenge_response(serverResponseBuilder.build())

        val challengeResponseBuilder = ChallengeResponse.Builder()
        challengeResponseBuilder.ts43_challenge_response(ts43ResponseBuilder.build())

        builder.verification(verificationBuilder.build())
        builder.challenge_response(challengeResponseBuilder.build())
        builder.header_(createRequestHeader(sessionId))

        return builder.build()
    }

    @SuppressLint("HardwareIds")
    private fun createRequestHeader(sessionId: String): RequestHeader {
        val instanceId = InstanceID.getInstance(context)
        val token = instanceId.getToken("496232013492", GcmConstants.INSTANCE_ID_SCOPE_GCM) // TODO: double check the authorizedEntity. Search "IidToken__asterism_project_number" in GmsCore
        // TODO cache/store?
        val keypair = genEcP256Keypair("gms-constellation-temp")
        val data = hashMapOf(
            "dg_androidId" to androidId.toString(16),
            "dg_session" to sessionId.toString(16),
            "dg_gmsCoreVersion" to com.google.android.gms.BuildConfig.VERSION_CODE.toString(),
            "dg_sdkVersion" to org.microg.gms.profile.Build.VERSION.SDK_INT.toString()
        )
//        val droidGuardResult = DroidGuardClient.getResults(context, "devicekey", data).await()
        lifecycleScope.launchWhenResumed {  }
        val dg = withContext(Dispatchers.IO) { DroidGuardClient.getResults(context, "attest", data).await() }

        return RequestHeader.build {
            client_info = ClientInfo.build {
                device_id = DeviceId.build {
                    iid_token = token

                    val id = Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ANDROID_ID,
                    ).toLong()
                    device_android_id = id // TODO: get the *primary* user id
                    user_android_id = id
                }
                client_public_key = keypair.public.encoded.toByteString()
                locale = Locale.getDefault().toString()
                gmscore_version_number = BuildConfig.VERSION_CODE / 1000
                gmscore_version = versionUtil.versionString
                android_sdk_version = Build.VERSION.SDK_INT
                device_signals = DeviceSignals.build {
                    droidguard_token = "TODO"
                }
                has_read_privileged_phone_state_permission = true
                country_info = CountryInfo.build {
                    sim_countries = listOf("TODO")
                    network_countries = listOf("TODO")
                }
                connectivity_infos = listOf() // TODO
                model = Build.MODEL
                manufacturer = Build.MANUFACTURER
                device_fingerprint = Build.FINGERPRINT
            }
            session_id = sessionId
            trigger = RequestTrigger.build {
                type = TriggerType.TRIGGER_TYPE_TRIGGER_API_CALL
            }
        }
    }

    private fun createGrpcClient(): PhoneDeviceVerificationClient {
         val client = OkHttpClient.Builder()
             .build()

         val grpcClient = GrpcClient.Builder()
             .client(client)
             .baseUrl(PHONE_VERIFICATION_BASE_URL)
             .build()

         return grpcClient.create(PhoneDeviceVerificationClient::class)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun genEcP256Keypair(alias: String): java.security.KeyPair {
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
        )
        kpg.initialize(
            KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")) // aka prime256v1
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512
                )
                .build()
        )
        return kpg.generateKeyPair()
    }

}