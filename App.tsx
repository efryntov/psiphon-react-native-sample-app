import React, { useEffect, useState } from 'react';
import { NativeModules, NativeEventEmitter, Button, Text, View, Switch, StyleSheet, ScrollView } from 'react-native';

const { PsiphonNativeModule } = NativeModules;

const NewModuleButton = () => {
  const [psiphonConnectionStateText, setPsiphonConnectionStateText] = useState("UNKNOWN");
  const [ipInfoData, setIpInfoData] = useState("IP info will be displayed here");
  const [usePsiphon, setUsePsiphon] = useState(false);

  useEffect(() => {
    const emitter = new NativeEventEmitter(PsiphonNativeModule)
    const subscription = emitter.addListener('PsiphonEvent', (data) => {
      console.log('PsiphonEvent', data);
      setPsiphonConnectionStateText(data.PsiphonConnectionState);
    });
  }, []);

  const onPress = async () => {
    setIpInfoData("Fetching IP info...");
    try {
      let ipinfo = await myFetch("https://ipinfo.io/json")
      setIpInfoData(ipinfo);
    } catch (error) {
      let errorMessage = "Failed to do something exceptional";
      if (error instanceof Error) {

        errorMessage = error.message;
      }
      setIpInfoData(errorMessage);
    }
  };

  const onSwitchChange = (value: boolean) => {
    setUsePsiphon(value);
    // Start or stop Psiphon right away on switch change
    value ? PsiphonNativeModule.startPsiphon() : PsiphonNativeModule.stopPsiphon();
  }

  const myFetch = async (url: string) => {
    // native fetch params: method: string, url: string, body: string, usePsiphon: boolean
    return PsiphonNativeModule.fetch("GET", url, null, usePsiphon);
  }

  return (
    <View style={styles.container}>
      <ScrollView style={styles.element}>
        <Text>
          {ipInfoData}
        </Text>
      </ScrollView>
      <View style={styles.element}>
        <Button
          title="Click to fetch IP info"
          color="#841584"
          onPress={onPress}
        />
      </View>
      <View style={styles.element}>
        <Text>
          Tunnel State: {psiphonConnectionStateText}
        </Text>
      </View>
      <View style={styles.element}>
        <View style={{ flexDirection: "row" }}>
          <Switch
            value={usePsiphon}
            onValueChange={onSwitchChange}
          />
          <Text>
            Use Psiphon
          </Text>
        </View>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    paddingHorizontal: 40,

    alignItems: 'center',
    justifyContent: 'center',
  },
  element: {
    padding: 20
  }
});

export default NewModuleButton;