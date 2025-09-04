import { NativeModules, NativeEventEmitter } from "react-native";

const { BackgroundLocationModule } = NativeModules;

const LocationEventEmitter = new NativeEventEmitter(BackgroundLocationModule);

const startTracking = (baseURL, header, params) => {
  if (!baseURL || !header) {
    throw new Error("Missing baseURL or header.");
  }

  // Pastikan `params` adalah objek
  const formattedParams = params || {};
  BackgroundLocationModule.startTracking(baseURL, header, formattedParams);
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
