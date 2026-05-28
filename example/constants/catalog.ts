import { Image } from 'react-native';
import type { ImageSourcePropType } from 'react-native';
import { CATEGORY_ACCENT, type Category } from './theme';

// ── Shapes ────────────────────────────────────────────────────────────────────

export interface CatalogItem {
  id: string;
  source: ImageSourcePropType;
  category: Category;
  accent: string;
  /** width / height ratio resolved at module load. */
  ratio: number;
}

export interface FootageItem {
  id: string;

  source: unknown;
  title: string;
}

// ── Raw image list ────────────────────────────────────────────────────────────

const IMAGE_SOURCES: Array<{
  source: ImageSourcePropType;
  category: Category;
}> = [
  {
    source: require('../assets/images/9ba092c8-2d9e-4614-8fb7-4774e45ab457.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/9bdee9a0-46f5-4ce2-afac-e2a63fe90b64.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/9c4345e2-63f4-4faa-b9ce-b1ee85d0c9d9.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/9c7b321d-75c3-48b3-b32b-21a9e598cb94.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/9c859e73-90cb-41b0-b4d3-5d7077fa788f.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/9c866008-e7ac-4016-a216-a3125bbe9bf6.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/9c949bae-ce97-4b67-a90a-34a384e03e63.jpg'),
    category: 'Animals',
  },
  {
    source: require('../assets/images/9cb34db0-13f3-4bd8-af5d-230abcfe8196.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/9cf24df8-e1b6-4eb2-833a-e3a9397408f7.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/9d452719-38ea-4fe8-b188-9e7c47fbf5e4.jpg'),
    category: 'Animals',
  },
  {
    source: require('../assets/images/9d465d07-f876-4c6a-a946-9e621fc6c363.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/9d8a4f0e-81f3-425f-ab85-8efc7e3cb5b6.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/9d8a4f92-9cd1-47d0-8f7c-9090c35a99e9.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/9d8a4fe4-aa56-4750-8bf3-d0dbc5a81a09.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/9d8a5043-09cd-408b-81b9-a62b15a1888c.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/9db1f69e-3e1e-4d18-b5dd-fb83b98fbd79.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/9dbdd505-96fe-4f90-87cd-69959ff09660.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/9e03f2d4-58fa-489d-96a7-25789b14b4c6.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/9e0c6501-1e8f-4428-8987-569ef1389449.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/9e10098e-866b-4c3d-8ca9-f5e8c0243f8f.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/9e1a38b2-b2c9-4744-afa8-9920acd07909.jpg'),
    category: 'Animals',
  },
  {
    source: require('../assets/images/9e1a63ae-9ec2-41bf-936b-84b076cbfe89.jpg'),
    category: 'Underwater',
  },
  {
    source: require('../assets/images/9e242635-a789-410b-8a3c-4ea625b7beeb.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/9e3c7299-6b8f-47e6-a549-83caa9572864.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/9e3e5add-485c-467d-827b-55cf402481d2.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/9e45edcf-0c74-4fa9-a180-5a5a7345eca8.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/9e59c49f-7700-4e5b-be14-32c070d5af43.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/9e7f4dc8-999f-4f22-87ed-201f8151cd64.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/9ef3281a-a251-4733-b2b4-166429b9737b.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/9f1d8d9a-39bd-4dd6-a65f-7b589c0a5bbd.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/9f256029-83b7-4cca-ab40-34aa82623fee.jpg'),
    category: 'Sports',
  },
  {
    source: require('../assets/images/9f4b5744-7ad6-42ae-a56a-c0a05830639c.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/9f6186cb-dda2-4df6-a261-3333cccf0276.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/9f61933b-541d-481c-8777-4c814188d6ff.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/9f65c275-5678-405b-828b-a8d6d3f836d1.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/9f6820da-c77a-49ce-9dd8-faf244f2b4d4.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/a01ec15d-8a16-40be-8c7b-b516b260c284.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/a02b026a-c630-4d4e-aae0-606e348c5de5.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/a0395656-c22e-4e94-9a49-480bbf5ca458.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/a03ad659-7fb7-454a-8636-c30e9f666810.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/a0738cb5-234c-45ee-b006-bcdc4c77919c.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/a07e2d49-8e4b-4818-a83e-400c0218c788.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/a07fb1ae-b523-4a39-972e-e689c061b1af.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/a0919f92-a2bf-4250-8ea2-e2637ac425d1.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/a0a9a58b-d856-415b-83a5-e6fa8394257c.jpg'),
    category: 'Animals',
  },
  {
    source: require('../assets/images/a0b1fa0d-1751-4a8f-8783-c8f82e697f71.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/a0bad78d-330b-42cb-943b-6c640766042f.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/a0bae6a4-d4a3-45e9-91da-b25b4e6a0068.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/a0c33dc8-7ea0-4c2f-afb3-52da8a7c0956.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/a0c52cce-1a34-4108-9d8e-54e6ed148ef2.jpg'),
    category: 'Underwater',
  },
  {
    source: require('../assets/images/a0ca83d6-d331-4b68-9f96-985ac8912b64.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/a0d5d70b-21be-4bac-86de-c1fd44a04ae1.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/a0f0e023-1596-4ecc-b771-b6717c96f67d.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/a0f1dd90-a6f4-4517-8b41-ad82bb2e96a8.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/a0f44c2b-de69-448d-9354-fad6324d4157.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/a0fe5092-ca25-464b-8ff6-dc0b55754a4d.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/a10406f4-b81b-4456-a6bb-313a8849ba00.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/a110becf-fc2a-455b-b63c-8f97c8605331.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/a112444e-aa0a-4def-a3ec-25e232a4af59.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/a1124490-93df-41d7-86a8-b08bab3fbd60.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/a1135b99-8f51-49a0-aa8d-a91f5a2e9d71.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/a1153d6f-02b7-4db8-b45e-0c315774161e.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/a1153da7-2686-4a2c-b575-2dbaec5c0fba.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/a138c014-260b-4154-a90d-46f62def4fc5.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/a1448704-8edc-4c67-a8aa-070551bc0686.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../assets/images/a188612c-9bdb-46ef-be68-d53e00fa8bd2.jpg'),
    category: 'Animals',
  },
  {
    source: require('../assets/images/a18f2c20-d4c5-460b-823f-84a9dd5c7f04.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/a1966656-2931-4e12-a72c-d40d432ab139.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../assets/images/a1b33a05-084c-4b3d-b7d8-ae9730b81d0c.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../assets/images/a1b33a0e-5350-4889-8019-89899658e505.jpg'),
    category: 'Nature',
  },
  {
    source: require('../assets/images/a1b944cf-6ed8-4def-a0e2-b8e899d07c13.jpg'),
    category: 'Architecture',
  },
  // ── Portrait (p- prefix) ──────────────────────────────────────────────────
  {
    source: require('../assets/images/p-9831466d-8d44-4716-a5e1-328e9366185e.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-9877b4d9-adbb-4bff-bc0b-4802f797d4ab.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-98946414-c532-4896-9311-a8eba52fa06d.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-98dc3a7a-5b07-4084-9156-101443846ce1.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-98f6662b-2d15-4e39-a953-e92f5ece2688.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-98f88e35-b619-4a0d-9d0c-d80d3a01b9cc.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-98fb1bc2-5c32-41c0-a1a0-bdc93f70846e.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-9922d15e-8f79-4dec-b2ad-c9fa1c84e531.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-9926659d-a433-465c-949a-2bdf83c79e52.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-9926bf31-ea3f-4b49-940b-0236446051d0.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-9926fd1b-76ff-4755-9664-7684569f688e.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-992e881a-3f8e-47de-b612-8d03f86b064b.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-995f1093-6418-4b75-8c7a-4b4e1fdd238d.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-995f1459-096f-46a9-a8ee-5ec5200a0ad0.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-996a4c1c-9dde-4f9e-836e-8c4f7bace057.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-99749745-9baa-4e9c-acd5-0d2cad2d2aa1.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-9974c95b-d704-45c4-b511-26b908446333.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-9974cfc4-6bbb-419a-8931-c11e169dce6c.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-99bb8611-7a4c-44fb-85bc-06ac11f7ef15.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-99bfd0cc-203e-4ffd-9ee1-6d13e0cbe29e.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../assets/images/p-99c2f6d5-9938-4b6c-bd28-20205c9b605a.jpg'),
    category: 'Portrait',
  },
];

// ── Build catalog ─────────────────────────────────────────────────────────────

export const CATALOG: CatalogItem[] = IMAGE_SOURCES.map((entry, i) => {
  const src = Image.resolveAssetSource(entry.source);
  const ratio = src?.width && src?.height ? src.width / src.height : 1;
  return {
    id: `i${String(i + 1).padStart(2, '0')}`,
    source: entry.source,
    category: entry.category,
    accent: CATEGORY_ACCENT[entry.category],
    ratio,
  };
});

const BY_ID = new Map<string, CatalogItem>(CATALOG.map((it) => [it.id, it]));

export const getCatalogItem = (id: string): CatalogItem | undefined =>
  BY_ID.get(id);

export const PORTRAIT_ITEMS = CATALOG.filter(
  (it) => it.category === 'Portrait'
);

// ── Category filter list ──────────────────────────────────────────────────────

export const CATEGORIES = [
  { label: 'All', accent: '#aaaaaa' },
  { label: 'Portrait' as Category, accent: CATEGORY_ACCENT.Portrait },
  { label: 'Nature' as Category, accent: CATEGORY_ACCENT.Nature },
  { label: 'Landscape' as Category, accent: CATEGORY_ACCENT.Landscape },
  { label: 'Architecture' as Category, accent: CATEGORY_ACCENT.Architecture },
  { label: 'Abstract' as Category, accent: CATEGORY_ACCENT.Abstract },
  { label: 'Animals' as Category, accent: CATEGORY_ACCENT.Animals },
  { label: 'Underwater' as Category, accent: CATEGORY_ACCENT.Underwater },
  { label: 'Sports' as Category, accent: CATEGORY_ACCENT.Sports },
];

// ── Footage list ──────────────────────────────────────────────────────────────

export const FOOTAGES: FootageItem[] = [
  {
    id: 'f01',
    source: require('../assets/footages/birds-clouds.mp4'),
    title: 'Birds & Clouds',
  },
  {
    id: 'f02',
    source: require('../assets/footages/crane-reflection.mp4'),
    title: 'Crane Reflection',
  },
  {
    id: 'f03',
    source: require('../assets/footages/dreamy-blur.mp4'),
    title: 'Dreamy Blur',
  },
  {
    id: 'f04',
    source: require('../assets/footages/dreamy-hands.mp4'),
    title: 'Dreamy Hands',
  },
  {
    id: 'f05',
    source: require('../assets/footages/power-lines.mp4'),
    title: 'Power Lines',
  },
  {
    id: 'f06',
    source: require('../assets/footages/Misty Dark Forest Trees Stock Video Footage Artgrid.io.mp4'),
    title: 'Dark Forest',
  },
  {
    id: 'f07',
    source: require('../assets/footages/Ethereal Dawn Sunrise Silhouette Stock Video Footage Artgrid.io.mp4'),
    title: 'Dawn',
  },
  {
    id: 'f08',
    source: require('../assets/footages/Volcanic Eruption Dramatic Nature Twilight Sky Ash Plume Stock Video Footage Artgrid.io.mp4'),
    title: 'Volcanic',
  },
  {
    id: 'f09',
    source: require('../assets/footages/Woman Mysterious Mood Dreamy Atmosphere Blur Stock Video Footage Artgrid.io.mp4'),
    title: 'Dreamy Woman',
  },
  {
    id: 'f10',
    source: require('../assets/footages/Anamorphic Effect Overlay Light Leak Stock Video Footage Artgrid.io.mp4'),
    title: 'Light Leak',
  },
];
