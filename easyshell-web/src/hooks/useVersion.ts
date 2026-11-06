import { useState, useEffect } from 'react';
import { getSystemVersion, type VersionInfo } from '../api/system';

let cachedVersion: VersionInfo | null = null;
let fetchPromise: Promise<VersionInfo | null> | null = null;

function fetchVersion(): Promise<VersionInfo | null> {
  if (cachedVersion) return Promise.resolve(cachedVersion);
  if (fetchPromise) return fetchPromise;

  fetchPromise = getSystemVersion()
    .then((res) => {
      if (res.code === 200 && res.data) {
        cachedVersion = res.data;
        return cachedVersion;
      }
      return null;
    })
    .catch(() => null)
    .finally(() => {
      fetchPromise = null;
    });

  return fetchPromise;
}

export function useVersion() {
  const [version, setVersion] = useState<VersionInfo | null>(cachedVersion);

  useEffect(() => {
    if (cachedVersion) return;
    fetchVersion().then((v) => {
      if (v) setVersion(v);
    });
  }, []);

  return version;
}
