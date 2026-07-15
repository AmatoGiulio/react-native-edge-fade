const ACCESS_KEY = 'ey-zRYDD6yj552TP1iTZaidxWxjN3qpRjDHEcCBch0k';
const BASE_URL = 'https://api.unsplash.com';

export interface UnsplashPhoto {
  id: string;
  slug: string;
  width: number;
  height: number;
  color: string;
  blur_hash: string | null;
  description: string | null;
  alt_description: string | null;
  urls: {
    raw: string;
    full: string;
    regular: string;
    small: string;
    thumb: string;
  };
  user: {
    id: string;
    username: string;
    name: string;
    portfolio_url: string | null;
    instagram_username: string | null;
  };
  links: {
    html: string;
    download: string;
  };
}

export interface UnsplashCollection {
  id: number;
  title: string;
  total_photos: number;
}

export async function searchCollections(
  query: string,
  perPage = 3
): Promise<UnsplashCollection[]> {
  const res = await fetch(
    `${BASE_URL}/search/collections?query=${encodeURIComponent(query)}&per_page=${perPage}`,
    { headers: { Authorization: `Client-ID ${ACCESS_KEY}` } }
  );
  if (!res.ok) throw new Error(`Unsplash error: ${res.status}`);
  const json = await res.json();
  return json.results as UnsplashCollection[];
}

export async function fetchCollectionPhotos(
  collectionId: number,
  perPage = 30,
  page = 1
): Promise<UnsplashPhoto[]> {
  const res = await fetch(
    `${BASE_URL}/collections/${collectionId}/photos?per_page=${perPage}&page=${page}`,
    { headers: { Authorization: `Client-ID ${ACCESS_KEY}` } }
  );
  if (!res.ok) throw new Error(`Unsplash error: ${res.status}`);
  return res.json() as Promise<UnsplashPhoto[]>;
}

export async function fetchPopularPhotos(
  perPage = 30,
  page = 1
): Promise<UnsplashPhoto[]> {
  const res = await fetch(
    `${BASE_URL}/photos?order_by=popular&per_page=${perPage}&page=${page}`,
    { headers: { Authorization: `Client-ID ${ACCESS_KEY}` } }
  );
  if (!res.ok) throw new Error(`Unsplash error: ${res.status}`);
  return res.json() as Promise<UnsplashPhoto[]>;
}

export async function fetchPhotoById(id: string): Promise<UnsplashPhoto> {
  const res = await fetch(`${BASE_URL}/photos/${id}`, {
    headers: { Authorization: `Client-ID ${ACCESS_KEY}` },
  });
  if (!res.ok) throw new Error(`Unsplash error: ${res.status}`);
  return res.json() as Promise<UnsplashPhoto>;
}
