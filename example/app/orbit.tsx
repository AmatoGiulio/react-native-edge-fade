import { StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { EdgeFadeView } from 'react-native-edge-fade';

const CLOUD = {
  uri: 'https://images.unsplash.com/photo-1534088568595-a066f410bcda?auto=format&fit=crop&w=1400&q=90',
};

export default function OrbitScreenshotScreen() {
  return (
    <View style={styles.screen}>
      <EdgeFadeView
        bottom={{ size: 430, curve: 'smoother' }}
        style={styles.hero}
      >
        <Image source={CLOUD} style={StyleSheet.absoluteFill} contentFit="cover" />
        <View style={styles.imageGrade} />
      </EdgeFadeView>

      <View style={styles.header}>
        <Text style={styles.brand}>ORBIT</Text>
        <View style={styles.actions}>
          <Text style={styles.plus}>+</Text>
          <View style={styles.person}>
            <View style={styles.head} />
            <View style={styles.shoulders} />
          </View>
        </View>
      </View>

      <Text style={styles.after}>After</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#090909',
  },
  hero: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#0b0b0b',
  },
  imageGrade: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(2, 10, 18, 0.24)',
  },
  header: {
    position: 'absolute',
    top: 54,
    left: 24,
    right: 24,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  brand: {
    color: '#f7f7f7',
    fontSize: 29,
    fontWeight: '400',
    letterSpacing: -1.1,
  },
  actions: {
    width: 132,
    height: 54,
    borderRadius: 27,
    backgroundColor: 'rgba(16, 32, 52, 0.76)',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-evenly',
  },
  plus: {
    color: '#f5f5f5',
    fontSize: 34,
    fontWeight: '300',
    lineHeight: 37,
    marginTop: -2,
  },
  person: {
    width: 28,
    height: 30,
    alignItems: 'center',
  },
  head: {
    width: 9,
    height: 9,
    borderRadius: 5,
    borderWidth: 2,
    borderColor: '#f5f5f5',
    marginTop: 1,
  },
  shoulders: {
    width: 18,
    height: 11,
    borderTopLeftRadius: 10,
    borderTopRightRadius: 10,
    borderWidth: 2,
    borderBottomWidth: 0,
    borderColor: '#f5f5f5',
    marginTop: 5,
  },
  after: {
    position: 'absolute',
    top: '72%',
    alignSelf: 'center',
    color: 'rgba(255,255,255,0.9)',
    fontSize: 43,
    fontWeight: '300',
    letterSpacing: -1.3,
  },
});
