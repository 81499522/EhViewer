//! Cache only immutable Java templates and weak mapping identities, never native page ownership.
use super::readonly_view;
use anyhow::{Result, anyhow, ensure};
use ehviewer_core::archive::{ReadOnlyIdentity, ReadOnlyPageBuffer};
use jni::{
    JNIEnv,
    objects::{GlobalRef, JByteBuffer},
};
use std::sync::Mutex;

struct Template {
    identity: ReadOnlyIdentity,
    object: GlobalRef,
}
static TEMPLATES: Mutex<Vec<Template>> = Mutex::new(Vec::new());
const MAX_TEMPLATES: usize = 4;

pub(super) fn view<'local>(
    env: &mut JNIEnv<'local>,
    data: &ReadOnlyPageBuffer,
) -> Result<JByteBuffer<'local>> {
    let identity = data.mapping_identity();
    if let Some(identity) = &identity {
        let cached = {
            let mut cache = TEMPLATES
                .lock()
                .map_err(|_| anyhow!("Template cache lock poisoned"))?;
            cache
                .iter()
                .position(|t| t.identity.same_backing(identity))
                .map(|index| {
                    let entry = cache.remove(index);
                    // Clones the JNI handle's Arc, not page bytes or a new JNI reference.
                    let object = GlobalRef::clone(&entry.object);
                    cache.push(entry);
                    object
                })
        };
        if let Some(template) = cached {
            // data is a live lease of the identical allocation/range. Weak identities keep
            // control blocks unique after destruction, so address reuse cannot match a stale
            // template. Templates are never returned to callers or read without this live lease.
            return readonly_view(env, template.as_obj());
        }
    }
    let bytes = data.as_slice();
    // SAFETY: data pins this immutable mapping/allocation through publication and registration.
    // The writable constructor wrapper is internal; only real read-only views are cached/returned.
    let direct = unsafe { env.new_direct_byte_buffer(bytes.as_ptr().cast_mut(), bytes.len())? };
    let template = readonly_view(env, &direct)?;
    let Some(identity) = identity else {
        return Ok(template);
    };
    let proposed = env.new_global_ref(&template)?;
    ensure!(
        !proposed.as_obj().is_null(),
        "Cannot retain read-only template"
    );
    let selected = {
        let mut cache = TEMPLATES
            .lock()
            .map_err(|_| anyhow!("Template cache lock poisoned"))?;
        if let Some(index) = cache
            .iter()
            .position(|t| t.identity.same_backing(&identity))
        {
            let existing = GlobalRef::clone(&cache[index].object);
            drop(cache);
            drop(proposed);
            existing
        } else {
            let selected = GlobalRef::clone(&proposed);
            let evicted = (cache.len() == MAX_TEMPLATES).then(|| cache.remove(0));
            cache.push(Template {
                identity,
                object: proposed,
            });
            drop(cache);
            // JNI cleanup stays outside the cache lock, including concurrent initialization.
            drop(evicted);
            selected
        }
    };
    // Return a fresh object even on the first extraction: caller positions/limits must never
    // mutate the internal template, and each registration retains its original exact identity.
    readonly_view(env, selected.as_obj())
}
