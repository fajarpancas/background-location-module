import { NativeModules, NativeEventEmitter } from "react-native";

const { BackgroundLocationModule } = NativeModules;

const LocationEventEmitter = new NativeEventEmitter(BackgroundLocationModule);

const startTracking = (baseURL, header) => {
  if (!baseURL || !header) {
    throw new Error("Missing baseURL or header.");
  }

  BackgroundLocationModule.startTracking(baseURL, header);
};

const stopTracking = () => {
  BackgroundLocationModule.stopTracking();
};

const addLocationListener = (callback) => {
  return LocationEventEmitter.addListener("LocationUpdated", callback);
};

export default {
  startTracking,
  stopTracking,
  addLocationListener,
};
