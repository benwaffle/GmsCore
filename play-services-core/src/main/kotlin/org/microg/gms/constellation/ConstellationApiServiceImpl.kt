/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.RemoteException
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.libraries.entitlement.ServiceEntitlementException
import com.android.libraries.entitlement.Ts43Authentication
import com.android.libraries.entitlement.Ts43Operation
import com.android.libraries.entitlement.odsa.AcquireTemporaryTokenOperation.AcquireTemporaryTokenRequest
import com.android.libraries.entitlement.utils.Ts43Constants
import com.squareup.wire.GrpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.Status
import com.google.android.gms.constellation.PhoneNumberVerification
import com.google.android.gms.constellation.VerifyPhoneNumberRequest
import com.google.android.gms.constellation.VerifyPhoneNumberResponse
import com.google.android.gms.constellation.internal.IConstellationApiService
import com.google.android.gms.constellation.internal.IConstellationCallbacks
import com.google.common.collect.ImmutableList
import org.microg.gms.phonenumberverification.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URL

class ConstellationApiServiceImpl(private val context: Context) : IConstellationApiService.Stub() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

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
                Log.d(TAG, "Standard verification for policy: ${request.upiPolicyId}")
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
        try {
            // Step 1: Send SyncRequest
            Log.d(TAG, "Sending SyncRequest via gRPC")
            val syncRequest = createSyncRequest()
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
            val proceedRequest = createProceedRequest(temporaryToken)

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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun createSyncRequest(): SyncRequest {
        val subs = subscriptionManager.activeSubscriptionInfoList

        return SyncRequest.build {
            verifications(
                subs?.map { sub ->
                    Verification.build {
                        state(VerificationState.VERIFICATION_STATE_NONE)
                        association(VerificationAssociation.build {
                            sim(createSimAssociation(sub))
                        })
                        telephony_info(createTelephonyInfo(sub))
                    }
                } ?: listOf()
            )
            header_(createRequestHeader())
        }
    }

    @SuppressLint("HardwareIds")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun createSimAssociation(sub: SubscriptionInfo): SIMAssociation {
        val subId = sub.subscriptionId
        val tm = telephonyManager.createForSubscriptionId(subId)

        return SIMAssociation.build {
            sim_info(SIMInfo.build {
                imsi(listOf(tm.subscriberId))
                sim_readable_number(subscriptionManager.getPhoneNumber(subId))
                telephony_phone_number(
                    listOf(
                        SubscriptionManager.PHONE_NUMBER_SOURCE_UICC,
                        SubscriptionManager.PHONE_NUMBER_SOURCE_IMS,
                        SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER,
                    )
                        .map { source -> source to subscriptionManager.getPhoneNumber(subId, source) }
                        .filter { it.second.isNotEmpty() }
                        .map { (source, number) ->
                            TelephonyPhoneNumber.build {
                                number(number)
                                source(when (source) {
                                    SubscriptionManager.PHONE_NUMBER_SOURCE_UICC -> PhoneNumberSource.PHONE_NUMBER_SOURCE_IUCC
                                    SubscriptionManager.PHONE_NUMBER_SOURCE_IMS -> PhoneNumberSource.PHONE_NUMBER_SOURCE_IMS
                                    SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER -> PhoneNumberSource.PHONE_NUMBER_SOURCE_CARRIER
                                    else -> PhoneNumberSource.PHONE_NUMBER_SOURCE_UNKNOWN
                                })
                            }
                        }
                )
                iccid(sub.iccId)
            })
            sim_slot(SIMSlot.build {
                sub_id(subId)
            })
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun createTelephonyInfo(sub: SubscriptionInfo): TelephonyInfo {
        val tm = telephonyManager.createForSubscriptionId(sub.subscriptionId)

        return TelephonyInfo.build {
            sim_state(SIMState.SIM_NOT_READY) // just based on what I saw from GMS
            group_id_level1(tm.groupIdLevel1)
            sim_operator(MobileOperatorInfo.build {
                country_code(tm.simCountryIso)
                mcc_mnc(tm.simOperator)
                operator_name(tm.simOperatorName)
            })
            network_operator(MobileOperatorInfo.build {
                country_code(tm.networkCountryIso)
                mcc_mnc(tm.networkOperator)
                operator_name(tm.networkOperatorName)
            })
            network_roaming(when (tm.isNetworkRoaming) {
                true -> RoamingState.ROAMING_STATE_ROAMING
                false -> RoamingState.ROAMING_STATE_NOT_ROAMING
            })
            data_roaming(when (tm.isDataRoamingEnabled) {
                true -> RoamingState.ROAMING_STATE_ROAMING
                false -> RoamingState.ROAMING_STATE_NOT_ROAMING
            })
            sms_capability(when (tm.isDeviceSmsCapable) {
                true -> SMSCapability.SMS_CAPABILITY_CAPABLE
                false -> SMSCapability.SMS_CAPABILITY_INCAPABLE
            })
        }
    }

    data class SimInfo(
        val subscriptionId: Int,
        val slotIndex: Int,
        val iccId: String?,
        val imsi: String?,          // subscriberId
        val number: String?,        // line1Number
        val gid1: String?,
        val mcc: String?,           // parsed mcc
        val mnc: String?,           // parsed mnc
        val operatorNumeric: String?, // simOperator (mcc+mnc) if available
        val operatorName: String?
    )

    fun Context.readAllSims(): List<SimInfo> {
        val out = mutableListOf<SimInfo>()
        val subManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val subs: List<SubscriptionInfo> = subManager.activeSubscriptionInfoList ?: return emptyList()

        for (sub in subs) {
            val subId = sub.subscriptionId
            val slot = sub.simSlotIndex

            // create telephony manager bound to this subscription
            val tmSub = try {
                tm.createForSubscriptionId(subId)
            } catch (e: SecurityException) {
                // fallback to base tm if createForSubscriptionId is forbidden
                Log.w("sim", "createForSubscriptionId failed for $subId: $e")
                tm
            }

            val iccId = try { sub.iccId } catch (t: Throwable) { null } // subscription info may give icc
            val imsi = try { tmSub.subscriberId } catch (t: Throwable) { null } // protected on non-priv apps
            val number = try { tmSub.line1Number } catch (t: Throwable) { null } // often null
            val gid1 = try { tmSub.groupIdLevel1 } catch (t: Throwable) { null } // privileged
            val operatorNumeric = try { tmSub.simOperator } catch (t: Throwable) { null } // "310260"
            val operatorName = try { tmSub.simOperatorName } catch (t: Throwable) { null }

            // parse mcc/mnc from numeric if present (numeric is mcc+mnc)
            val (mcc, mnc) = if (!operatorNumeric.isNullOrEmpty() && operatorNumeric.length >= 5) {
                // mcc is first 3 digits; mnc is the rest (2-3 digits)
                val mccPart = operatorNumeric.substring(0, 3)
                val mncPart = operatorNumeric.substring(3)
                Pair(mccPart, mncPart)
            } else {
                Pair(null, null)
            }

            out += SimInfo(
                subscriptionId = subId,
                slotIndex = slot,
                iccId = iccId,
                imsi = imsi,
                number = number,
                gid1 = gid1,
                mcc = mcc,
                mnc = mnc,
                operatorNumeric = operatorNumeric,
                operatorName = operatorName
            )
        }

        return out
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

    private fun createProceedRequest(temporaryToken: String): ProceedRequest {
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
        builder.header(createRequestHeader())

        return builder.build()
    }

    private fun createRequestHeader(): RequestHeader {
        val builder = RequestHeader.Builder()
        // Add required client information
        // This should be populated with actual device and client info
        return builder.build()
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
}