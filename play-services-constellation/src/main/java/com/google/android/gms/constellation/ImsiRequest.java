package com.google.android.gms.constellation;

import org.microg.safeparcel.AutoSafeParcelable;

public class ImsiRequest extends AutoSafeParcelable {
    @Field(1)
    String imsi;
    @Field(2)
    String msisdn;

    @Override
    public String toString() {
        return "ImsiRequest{" +
                "a='" + imsi + '\'' +
                ", msisdn='" + msisdn + '\'' +
                '}';
    }

    public static final Creator<ImsiRequest> CREATOR = findCreator(ImsiRequest.class);
}
