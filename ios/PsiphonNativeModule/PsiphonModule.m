#import "React/RCTBridgeModule.h"
#import "React/RCTEventEmitter.h"

@interface RCT_EXTERN_MODULE(PsiphonNativeModule, RCTEventEmitter)
RCT_EXTERN_METHOD(startPsiphon: (NSString *)config resolver: (RCTPromiseResolveBlock)resolve rejecter: (RCTPromiseRejectBlock)reject)
RCT_EXTERN_METHOD(stopPsiphon)
@end
