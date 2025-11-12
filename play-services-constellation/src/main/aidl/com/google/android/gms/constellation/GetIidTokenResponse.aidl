package com.google.android.gms.constellation;

parcelable GetIidTokenResponse {
    String iidToken;
    String fid;
    byte[] clientSignature;
    long currentTimeMs;
}
