const path = require('path');
const { getConfig } = require('react-native-builder-bob/babel-config');
const pkg = require('../package.json');

const root = path.resolve(__dirname, '..');

module.exports = function (api) {
  api.cache(true);

  return getConfig(
    {
      presets: ['babel-preset-expo'],
      // Required for Reanimated worklet transforms (useAnimatedProps,
      // useAnimatedScrollHandler, etc.).  Must be last in the plugins list.
      plugins: ['react-native-reanimated/plugin'],
    },
    { root, pkg }
  );
};
