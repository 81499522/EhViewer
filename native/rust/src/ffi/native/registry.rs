//! Address-indexed registrations with constant-time budget accounting and exact-object removal.
use anyhow::{Result, ensure};
use std::collections::BTreeMap;

struct Entry<T> {
    bytes: usize,
    retained_bytes: usize,
    value: T,
}
pub(super) struct Registry<T> {
    entries: BTreeMap<usize, Vec<Entry<T>>>,
    bytes: usize,
    count: usize,
    max_bytes: usize,
    max_count: usize,
}
impl<T> Registry<T> {
    pub(super) const fn new(max_count: usize, max_bytes: usize) -> Self {
        Self {
            entries: BTreeMap::new(),
            bytes: 0,
            count: 0,
            max_bytes,
            max_count,
        }
    }
    pub(super) fn insert(
        &mut self,
        address: usize,
        bytes: usize,
        retained_bytes: usize,
        value: T,
    ) -> Result<()> {
        ensure!(address.checked_add(bytes).is_some(), "Invalid buffer range");
        ensure!(retained_bytes >= bytes, "Invalid buffer capacity");
        ensure!(
            self.count < self.max_count && retained_bytes <= self.max_bytes - self.bytes,
            "Native buffer budget exceeded"
        );
        self.entries.entry(address).or_default().push(Entry {
            bytes,
            retained_bytes,
            value,
        });
        self.bytes += retained_bytes;
        self.count += 1;
        Ok(())
    }
    pub(super) fn remove(
        &mut self,
        address: usize,
        mut matches: impl FnMut(&T) -> Result<bool>,
    ) -> Result<Option<T>> {
        let Some(bucket) = self.entries.get_mut(&address) else {
            return Ok(None);
        };
        let mut found = None;
        for (index, entry) in bucket.iter().enumerate() {
            if matches(&entry.value)? {
                found = Some(index);
                break;
            }
        }
        let Some(index) = found else {
            return Ok(None);
        };
        let entry = bucket.swap_remove(index);
        self.bytes -= entry.retained_bytes;
        self.count -= 1;
        if bucket.is_empty() {
            self.entries.remove(&address);
        }
        Ok(Some(entry.value))
    }
    pub(super) fn covering(&self, address: usize, bytes: usize) -> Option<&T> {
        let end = address.checked_add(bytes)?;
        let (&base, bucket) = self.entries.range(..=address).next_back()?;
        bucket
            .iter()
            .find(|entry| end <= base + entry.bytes)
            .map(|entry| &entry.value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn budgets_and_exact_identity_survive_address_reuse() {
        let mut registry = Registry::new(2, 16);
        registry.insert(100, 8, 8, 1).unwrap();
        registry.insert(200, 8, 8, 2).unwrap();
        assert!(registry.insert(300, 1, 1, 3).is_err());
        assert_eq!(registry.remove(100, |id| Ok(*id == 99)).unwrap(), None);
        assert_eq!(registry.remove(100, |id| Ok(*id == 1)).unwrap(), Some(1));
        registry.insert(100, 8, 8, 3).unwrap();
        assert_eq!(registry.remove(100, |id| Ok(*id == 1)).unwrap(), None);
        assert_eq!((registry.count, registry.bytes), (2, 16));
        assert_eq!(registry.remove(100, |_| Ok(true)).unwrap(), Some(3));
        assert_eq!(registry.remove(100, |_| Ok(true)).unwrap(), None);
    }

    #[test]
    fn empty_buffers_views_and_retained_lease() {
        let mut registry = Registry::new(4, 16);
        registry.insert(1, 0, 0, Arc::new(1)).unwrap();
        registry.insert(1, 0, 0, Arc::new(2)).unwrap();
        assert_eq!(*registry.remove(1, |id| Ok(**id == 2)).unwrap().unwrap(), 2);
        let allocation = Arc::new(3);
        let weak = Arc::downgrade(&allocation);
        registry.insert(100, 16, 16, allocation).unwrap();
        let lease = registry.covering(104, 12).unwrap().clone();
        assert!(registry.covering(104, 13).is_none());
        assert!(registry.covering(usize::MAX, 2).is_none());
        assert!(registry.insert(usize::MAX, 2, 2, Arc::new(4)).is_err());
        drop(registry.remove(100, |_| Ok(true)).unwrap());
        assert!(weak.upgrade().is_some());
        drop(lease);
        assert!(weak.upgrade().is_none());
    }

    #[test]
    fn retained_capacity_is_charged_without_exposing_spare_bytes() {
        let mut registry = Registry::new(2, 16);
        registry.insert(100, 4, 16, 1).unwrap();
        assert!(registry.covering(100, 4).is_some());
        assert!(registry.covering(100, 5).is_none());
        assert!(registry.insert(200, 1, 1, 2).is_err());
        assert!(registry.insert(200, 2, 1, 2).is_err());
        registry.remove(100, |_| Ok(true)).unwrap();
        assert_eq!((registry.count, registry.bytes), (0, 0));
        registry.insert(200, 1, 16, 2).unwrap();
    }
}
