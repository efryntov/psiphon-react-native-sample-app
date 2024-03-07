import React, { useEffect, useState } from 'react';
import { NativeModules, NativeEventEmitter, Button, Text, View, Switch, StyleSheet, ScrollView } from 'react-native';

const { PsiphonNativeModule } = NativeModules;

const NewModuleButton = () => {
  const [psiphonConnectionStateText, setPsiphonConnectionStateText] = useState("UNKNOWN");
  const [ipInfoData, setIpInfoData] = useState("IP info will be displayed here");
  const [usePsiphon, setUsePsiphon] = useState(false);

  useEffect(() => {
    const emitter = new NativeEventEmitter(PsiphonNativeModule);
    const subscription = emitter.addListener('PsiphonConnectionState', (data) => {
      console.log(data);
      if (data && data.state)
        setPsiphonConnectionStateText(data.state);
    });
  
    // Cleanup function to unsubscribe
    return () => {
      subscription.remove();
    };
  }, []);

  const onPress = async () => {
    setIpInfoData("Fetching IP info...");

    fetch("https://ipinfo.io/json").then((response) => response.text()).then((data) => {
      setIpInfoData(data);
    }).catch((error) => {
      setIpInfoData("Error fetching IP info");
    });
  }

  const onSwitchChange = async (value: boolean) => {
    setUsePsiphon(value);
    // Start or stop Psiphon right away on switch change
    if (value) {
      // Load the config from the assets folder
      // TODO: use fetch or a file reading library to load the config?
      const config = require('./assets/psiphon_config.json');

      // Start Psiphon with the config
      startPsiphon(JSON.stringify(config));
    } else {
      stopPsiphon();
    }
  }

  const startPsiphon = async (config: string) => {
    try {
      await PsiphonNativeModule.startPsiphon(config);
    } catch (error) {
      console.log(error);
    }
  };

  const stopPsiphon = () => {
    PsiphonNativeModule.stopPsiphon();
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