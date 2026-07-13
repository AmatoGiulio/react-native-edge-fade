import { useMemo } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import {
  searchCollections,
  fetchCollectionPhotos,
  fetchPopularPhotos,
  fetchPhotoById,
  type UnsplashPhoto,
} from './unsplash';

export interface CatalogItem {
  id: string;
  source: { uri: string };
  title: string;
  accent: string;
  ratio: number;
  blur_hash: string | null;
  color: string;
  author: string;
  description: string | null;
  alt_description: string | null;
  width: number;
  height: number;
}

function toCatalogItem(photo: UnsplashPhoto): CatalogItem {
  return {
    id: photo.id,
    source: { uri: photo.urls.regular },
    title:
      photo.alt_description ??
      photo.description ??
      `Photo by ${photo.user.name}`,
    accent: photo.color,
    ratio: photo.width / photo.height,
    blur_hash: photo.blur_hash,
    color: photo.color,
    author: photo.user.name,
    description: photo.description,
    alt_description: photo.alt_description,
    width: photo.width,
    height: photo.height,
  };
}

export function useCatalog() {
  const collectionQuery = useQuery({
    queryKey: ['unsplash', 'search-collection', 'minimal'],
    queryFn: () => searchCollections('birds'),
    staleTime: 7 * 24 * 60 * 60 * 1000,
    retry: 1,
  });

  const collectionId = collectionQuery.data?.[0]?.id;

  const collectionPhotos = useQueries({
    queries: [1, 2].map((page) => ({
      queryKey: ['unsplash', 'collection-photos', collectionId, page],
      queryFn: () => fetchCollectionPhotos(collectionId!, 30, page),
      enabled: !!collectionId,
      staleTime: 30 * 60 * 1000,
      retry: 1,
    })),
  });

  const allCollectionData = collectionPhotos.flatMap(
    (q) => q.data ?? []
  );

  const fallbackQuery = useQuery({
    queryKey: ['unsplash', 'popular-fallback'],
    queryFn: () => fetchPopularPhotos(30, 1),
    enabled: collectionQuery.isFetched && !collectionId,
    staleTime: 30 * 60 * 1000,
    retry: 1,
  });

  const data = allCollectionData.length > 0
    ? allCollectionData
    : (fallbackQuery.data ?? []);

  const loadingCollection =
    collectionQuery.isLoading ||
    collectionPhotos.some((q) => q.isLoading);
  const loadingFallback =
    collectionQuery.isFetched && !collectionId && fallbackQuery.isLoading;

  const isLoading = loadingCollection || loadingFallback;

  const hasError =
    collectionQuery.isError ||
    collectionPhotos.some((q) => q.isError) ||
    fallbackQuery.isError;
  const isError = !isLoading && data.length === 0 && hasError;

  const catalog = useMemo(() => data.map(toCatalogItem), [data]);

  return { catalog, isLoading, isError };
}

export function useCatalogItem(id: string) {
  const { catalog, isLoading: catLoading } = useCatalog();

  const fromCache = useMemo(
    () => catalog.find((it) => it.id === id),
    [catalog, id]
  );

  const { data: directPhoto, isLoading: directLoading } = useQuery({
    queryKey: ['unsplash', 'photo', id],
    queryFn: () => fetchPhotoById(id),
    enabled: !fromCache && !catLoading && !!id,
    staleTime: 30 * 60 * 1000,
  });

  const item = useMemo(() => {
    if (fromCache) return fromCache;
    if (directPhoto) return toCatalogItem(directPhoto);
    return null;
  }, [fromCache, directPhoto]);

  return { item, isLoading: catLoading || directLoading };
}
