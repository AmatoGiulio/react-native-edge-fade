const {
  withAppBuildGradle,
  withGradleProperties,
} = require('expo/config-plugins');

// The example opts into the official alpha. Library consumers keep the
// dependency-free default unless they explicitly enable this Gradle property.
module.exports = function withOfficialAndroidxBlur(config) {
  config = withGradleProperties(config, (config) => {
    config.modResults = config.modResults.filter(
      (entry) => entry.key !== 'edgeFadeAndroidxBlur'
    );
    config.modResults.push({
      type: 'property',
      key: 'edgeFadeAndroidxBlur',
      value: 'true',
    });
    return config;
  });

  return withAppBuildGradle(config, (config) => {
    if (config.modResults.language !== 'groovy') {
      throw new Error('The official blur example expects a Groovy app build.');
    }
    const marker = '// Edge Fade official AndroidX blur compile SDK';
    if (!config.modResults.contents.includes(marker)) {
      config.modResults.contents += `
${marker}
if ((findProperty("edgeFadeAndroidxBlur") ?: "false").toBoolean()) {
    android {
        compileSdk = 37
        compileSdkMinor = 1
    }
}
`;
    }
    return config;
  });
};
