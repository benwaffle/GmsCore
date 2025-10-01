/*
 * Copyright (C) 2013-2025 microG Project Team
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

package org.microg.gms.constellation

import android.os.RemoteException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import org.microg.gms.BaseService
import org.microg.gms.common.GmsService

/**
 * Constellation service endpoint for phone number verification.
 * Implements TS43 authentication flow for carrier-based verification.
 */
class ConstellationService : BaseService("GmsConstellationSvc", GmsService.CONSTELLATION) {

    private val api = ConstellationApiServiceImpl(this)

    @Throws(RemoteException::class)
    override fun handleServiceRequest(
        callback: IGmsCallbacks,
        request: GetServiceRequest,
        service: GmsService
    ) {
        val conn = ConnectionInfo().apply {
            features = arrayOf(
//                Feature("asterism_consent", 3),
                Feature("verify_phone_number", 2),
//                Feature("get_iid_token", 1),
//                Feature("verify_phone_number_local_read", 1)
            )
        }
        callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS, api.asBinder(), conn)
    }
}