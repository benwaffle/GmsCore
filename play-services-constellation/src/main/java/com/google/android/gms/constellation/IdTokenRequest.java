package com.google.android.gms.constellation;

import androidx.annotation.NonNull;

import org.microg.safeparcel.AutoSafeParcelable;

public class IdTokenRequest extends AutoSafeParcelable {
    @Field(1)
    String a;
    @Field(2)
    String b;

    @NonNull
    @Override
    public String toString() {
        return "IdTokenRequest{" +
                "a='" + a + '\'' +
                ", b='" + b + '\'' +
                '}';
    }

    public static final Creator<IdTokenRequest> CREATOR = findCreator(IdTokenRequest.class);
}
