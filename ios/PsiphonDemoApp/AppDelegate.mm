#import "AppDelegate.h"

#import <React/RCTBundleURLProvider.h>
#import <React/RCTHTTPRequestHandler.h>

#import "PsiphonDemoApp-Swift.h"

@implementation AppDelegate

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
{
    self.moduleName = @"PsiphonDemoApp";
    // You can add your custom initial props in the dictionary below.
    // They will be passed down to the ViewController used by React Native.
    self.initialProps = @{};

    RCTSetCustomNSURLSessionConfigurationProvider(^NSURLSessionConfiguration *{
        return PsiphonTunnelDelegate.shared.getURLSessionConfiguration;
    });

    BOOL result = [super application:application didFinishLaunchingWithOptions:launchOptions];

    RCTBridge *bridge = self.bridge;

    [PsiphonTunnelDelegate.shared setBridge:bridge];

    return result;
}

- (NSURL *)sourceURLForBridge:(RCTBridge *)bridge
{
    return [self getBundleURL];
}

- (NSURL *)getBundleURL
{
#if DEBUG
    return [[RCTBundleURLProvider sharedSettings] jsBundleURLForBundleRoot:@"index"];
#else
    return [[NSBundle mainBundle] URLForResource:@"main" withExtension:@"jsbundle"];
#endif
}

@end
