package com.google.android.gms.constellation;

import android.os.Bundle;
import android.telephony.TelephonyManager;

import org.microg.safeparcel.AutoSafeParcelable;

import java.util.List;

public class VerifyPhoneNumberRequest extends AutoSafeParcelable {
    @Field(1)
    public String upiPolicyId;
    @Field(2)
    public long b;
    @Field(3)
    public IdTokenRequest c;
    @Field(4)
    public Bundle settings;
    @Field(5)
    public List<ImsiRequest> e;
    @Field(6)
    public boolean f;
    @Field(7)
    public int g;
    @Field(8)
    public List h;

    @Override
    public String toString() {
        return "VerifyPhoneNumberRequest{" +
                "upiPolicyId='" + upiPolicyId + '\'' +
                ", b=" + b +
                ", c=" + c +
                ", settings=" + bundleToString(settings) +
                ", e=" + e +
                ", f=" + f +
                ", g=" + g +
                ", h=" + h +
                '}';
    }

    public static String bundleToString(Bundle b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder("Bundle{");
        for (String key : b.keySet()) {
            sb.append(key).append("=").append(b.get(key)).append(", ");
        }
        if (sb.length() > 7) sb.setLength(sb.length()-2);
        sb.append("}");
        return sb.toString();
    }

    public static final Creator<VerifyPhoneNumberRequest> CREATOR = findCreator(VerifyPhoneNumberRequest.class);
}
