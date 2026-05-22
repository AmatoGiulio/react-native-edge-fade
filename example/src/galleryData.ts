import { Image } from 'react-native';
import type { ImageSourcePropType } from 'react-native';

import { COLORS } from './theme';

export const CATEGORY_ACCENT = {
  Architecture: '#60a5fa',
  Portrait: '#f472b6',
  Animals: '#34d399',
  Abstract: '#a78bfa',
  Nature: '#22c55e',
  Landscape: '#f59e0b',
  Sports: '#94a3b8',
  Underwater: '#06b6d4',
} as const;

export type CategoryName = keyof typeof CATEGORY_ACCENT;

export type GalleryItem = {
  id: string;
  source: ImageSourcePropType;
  category: CategoryName;
  accent: string;
};

export type CategoryFilter = {
  label: 'All' | CategoryName;
  accent: string;
};

type ResolvedAsset = {
  width?: number;
  height?: number;
};

const photo = (
  id: string,
  source: ImageSourcePropType,
  category: CategoryName
): GalleryItem => ({
  id,
  source,
  category,
  accent: CATEGORY_ACCENT[category],
});

export const ITEMS: GalleryItem[] = [
  photo(
    'i01',
    require('../assets/images/9ba092c8-2d9e-4614-8fb7-4774e45ab457.jpg'),
    'Architecture'
  ),
  photo(
    'i02',
    require('../assets/images/9c4345e2-63f4-4faa-b9ce-b1ee85d0c9d9.jpg'),
    'Portrait'
  ),
  photo(
    'i03',
    require('../assets/images/9d4529fe-f2ae-4ab2-a29f-f14fc999cf0c.jpg'),
    'Animals'
  ),
  photo(
    'i04',
    require('../assets/images/9d8a4f0e-81f3-425f-ab85-8efc7e3cb5b6.jpg'),
    'Abstract'
  ),
  photo(
    'i05',
    require('../assets/images/9d8a4f92-9cd1-47d0-8f7c-9090c35a99e9.jpg'),
    'Nature'
  ),
  photo(
    'i06',
    require('../assets/images/9d8a4fe4-aa56-4750-8bf3-d0dbc5a81a09.jpg'),
    'Portrait'
  ),
  photo(
    'i07',
    require('../assets/images/9d8a5043-09cd-408b-81b9-a62b15a1888c.jpg'),
    'Portrait'
  ),
  photo(
    'i08',
    require('../assets/images/9e242635-a789-410b-8a3c-4ea625b7beeb.jpg'),
    'Portrait'
  ),
  photo(
    'i09',
    require('../assets/images/9e3c7299-6b8f-47e6-a549-83caa9572864.jpg'),
    'Nature'
  ),
  photo(
    'i10',
    require('../assets/images/9e3e5add-485c-467d-827b-55cf402481d2.jpg'),
    'Abstract'
  ),
  photo(
    'i11',
    require('../assets/images/9ef3281a-a251-4733-b2b4-166429b9737b.jpg'),
    'Landscape'
  ),
  photo(
    'i12',
    require('../assets/images/9f256029-83b7-4cca-ab40-34aa82623fee.jpg'),
    'Sports'
  ),
  photo(
    'i13',
    require('../assets/images/9f4b5744-7ad6-42ae-a56a-c0a05830639c.jpg'),
    'Architecture'
  ),
  photo(
    'i14',
    require('../assets/images/9f6820da-c77a-49ce-9dd8-faf244f2b4d4.jpg'),
    'Portrait'
  ),
  photo(
    'i15',
    require('../assets/images/a0395656-c22e-4e94-9a49-480bbf5ca458.jpg'),
    'Landscape'
  ),
  photo(
    'i16',
    require('../assets/images/a03ad659-7fb7-454a-8636-c30e9f666810.jpg'),
    'Portrait'
  ),
  photo(
    'i17',
    require('../assets/images/a07e2d49-8e4b-4818-a83e-400c0218c788.jpg'),
    'Portrait'
  ),
  photo(
    'i18',
    require('../assets/images/a0ca83d6-d331-4b68-9f96-985ac8912b64.jpg'),
    'Nature'
  ),
  photo(
    'i19',
    require('../assets/images/a0d5d70b-21be-4bac-86de-c1fd44a04ae1.jpg'),
    'Abstract'
  ),
  photo(
    'i20',
    require('../assets/images/a0f1dd90-a6f4-4517-8b41-ad82bb2e96a8.jpg'),
    'Landscape'
  ),
  photo(
    'i21',
    require('../assets/images/a0f44c2b-de69-448d-9354-fad6324d4157.jpg'),
    'Architecture'
  ),
  photo(
    'i22',
    require('../assets/images/a110becf-fc2a-455b-b63c-8f97c8605331.jpg'),
    'Portrait'
  ),
  photo(
    'i23',
    require('../assets/images/a1124490-93df-41d7-86a8-b08bab3fbd60.jpg'),
    'Abstract'
  ),
  photo(
    'i24',
    require('../assets/images/a1135b99-8f51-49a0-aa8d-a91f5a2e9d71.jpg'),
    'Nature'
  ),
  photo(
    'i25',
    require('../assets/images/a1153da7-2686-4a2c-b575-2dbaec5c0fba.jpg'),
    'Abstract'
  ),
  photo(
    'i26',
    require('../assets/images/a120e421-b303-4145-be24-f3f8dc77da2b.jpg'),
    'Underwater'
  ),
  photo(
    'i27',
    require('../assets/images/a16ee596-df8e-4469-91d2-f39f162fb184.jpg'),
    'Nature'
  ),
  photo(
    'i28',
    require('../assets/images/p-9877b4d9-adbb-4bff-bc0b-4802f797d4ab.jpg'),
    'Portrait'
  ),
  photo(
    'i29',
    require('../assets/images/p-98946414-c532-4896-9311-a8eba52fa06d.jpg'),
    'Architecture'
  ),
  photo(
    'i30',
    require('../assets/images/p-98dc3a7a-5b07-4084-9156-101443846ce1.jpg'),
    'Portrait'
  ),
  photo(
    'i31',
    require('../assets/images/p-98f88e35-b619-4a0d-9d0c-d80d3a01b9cc.jpg'),
    'Abstract'
  ),
  photo(
    'i32',
    require('../assets/images/p-98fb1bc2-5c32-41c0-a1a0-bdc93f70846e.jpg'),
    'Nature'
  ),
  photo(
    'i33',
    require('../assets/images/p-99bb8611-7a4c-44fb-85bc-06ac11f7ef15.jpg'),
    'Landscape'
  ),
];

export const CATEGORIES: CategoryFilter[] = [
  { label: 'All', accent: COLORS.text },
  ...Object.entries(CATEGORY_ACCENT).map(([label, accent]) => ({
    label: label as CategoryName,
    accent,
  })),
];

const imageResolver = (
  Image as typeof Image & {
    resolveAssetSource?: (source: ImageSourcePropType) => ResolvedAsset;
  }
).resolveAssetSource;

const assetRatio = new Map(
  ITEMS.map((item) => {
    const source =
      imageResolver?.(item.source) ??
      (typeof item.source === 'object' ? (item.source as ResolvedAsset) : null);
    const ratio =
      source?.width && source.height ? source.width / source.height : 1;

    return [item.id, ratio];
  })
);

export const ratioFor = (item: GalleryItem) => assetRatio.get(item.id) ?? 1;

export function splitColumns(items: GalleryItem[]) {
  const left: GalleryItem[] = [];
  const right: GalleryItem[] = [];
  let leftHeight = 0;
  let rightHeight = 0;

  for (const item of items) {
    const height = 1 / ratioFor(item);
    if (leftHeight <= rightHeight) {
      left.push(item);
      leftHeight += height;
    } else {
      right.push(item);
      rightHeight += height;
    }
  }

  return { left, right };
}
