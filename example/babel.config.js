module.exports = function (api) {
  api.cache(true);

  return {
    presets: ['babel-preset-expo'],
    // Required for Reanimated worklet transforms (useAnimatedProps,
    // useAnimatedScrollHandler, etc.).  Must be last in the plugins list.
    plugins: ['react-native-reanimated/plugin'],
  };
};
