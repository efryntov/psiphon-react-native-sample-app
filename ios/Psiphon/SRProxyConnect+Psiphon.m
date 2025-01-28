#import "SRProxyConnect+Psiphon.h"
#import <objc/message.h>

@implementation SRProxyConnect (Psiphon)

+ (void)load {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        Class class = [self class];
        SEL originalSelector = NSSelectorFromString(@"_configureProxy");
        SEL swizzledSelector = @selector(psiphon_configureProxy);

        Method originalMethod = class_getInstanceMethod(class, originalSelector);
        Method swizzledMethod = class_getInstanceMethod(class, swizzledSelector);

        NSAssert(originalMethod, @"Original method _configureProxy not found - implementation likely changed");

        method_exchangeImplementations(originalMethod, swizzledMethod);
    });
}

- (void)psiphon_configureProxy {
    NSURL *url = [self valueForKey:@"_url"];
    Class psiphonClass = NSClassFromString(@"PsiphonDemoApp.PsiphonTunnelDelegate");

    if (psiphonClass) {
        SEL sharedSelector = NSSelectorFromString(@"shared");
        SEL proxyConfigSelector = NSSelectorFromString(@"getProxyConfig:completion:");

        if ([psiphonClass respondsToSelector:sharedSelector]) {
            id shared = ((id (*)(id, SEL))objc_msgSend)(psiphonClass, sharedSelector);
            if ([shared respondsToSelector:proxyConfigSelector]) {
                __weak typeof(self) weakSelf = self;

                ((void (*)(id, SEL, id, void (^)(NSDictionary *)))objc_msgSend)(
                    shared,
                    proxyConfigSelector,
                    url,
                    ^(NSDictionary *proxyConfig) {
                        __strong typeof(weakSelf) strongSelf = weakSelf;
                        if (!strongSelf) return;

                        if (proxyConfig) {
                            NSLog(@"Using Psiphon proxy configuration for %@", url);
                            SEL readProxySelector = NSSelectorFromString(@"_readProxySettingWithType:settings:");
                            if ([strongSelf respondsToSelector:readProxySelector]) {
                                NSString *proxyType = proxyConfig[(NSString *)kCFProxyTypeKey];
                                ((void (*)(id, SEL, NSString *, NSDictionary *))objc_msgSend)(
                                    strongSelf,
                                    readProxySelector,
                                    proxyType,
                                    proxyConfig
                                );
                            }
                        }

                        SEL openConnectionSelector = NSSelectorFromString(@"_openConnection");
                        if ([strongSelf respondsToSelector:openConnectionSelector]) {
                            ((void (*)(id, SEL))objc_msgSend)(strongSelf, openConnectionSelector);
                        }
                    }
                );
                return;
            }
        }
    }

    // Call original implementation
    ((void(*)(id, SEL))objc_msgSend)(self, @selector(psiphon_configureProxy));
}

@end
