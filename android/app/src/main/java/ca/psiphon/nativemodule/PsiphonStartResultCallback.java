package ca.psiphon.nativemodule;

interface PsiphonStartResultCallback {
    void onSuccess();
    void onError(Exception e);
}
