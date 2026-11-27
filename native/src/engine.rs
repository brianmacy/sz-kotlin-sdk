//! `SzEngine` native methods. Strings the engine returns pass through unchanged; an empty
//! string means "none" (no info, no redo record, end of export), as in `sz_rust_sdk`.

use crate::bridge::{Outcome, json, new_string, optional_string, run, string};
use crate::environment::engine;
use jni::objects::{JClass, JString};
use jni::sys::jlong;
use jni::{Env, EnvUnowned};
use std::collections::HashSet;
use sz_rust_sdk::prelude::*;

fn flags(bits: jlong) -> Option<SzFlags> {
    Some(SzFlags::from_bits_retain(bits as u64))
}

/// Record keys, encoded by Kotlin as `[["DATA_SOURCE","RECORD_ID"], ...]`.
fn record_keys(text: &str) -> Outcome<Vec<(String, String)>> {
    json(text)
}

fn borrowed(keys: &[(String, String)]) -> Vec<(&str, &str)> {
    keys.iter().map(|(d, r)| (d.as_str(), r.as_str())).collect()
}

/// An optional JSON array argument (`null` when the set is empty).
fn optional_set<T>(env: &Env<'_>, value: &JString<'_>) -> Outcome<Option<HashSet<T>>>
where
    T: serde::de::DeserializeOwned + Eq + std::hash::Hash,
{
    optional_string(env, value)?
        .map(|text| json::<HashSet<T>>(&text))
        .transpose()
}

/// A native method returning the engine's JSON text.
fn text<'local>(
    env: &mut EnvUnowned<'local>,
    body: impl FnOnce(&mut Env<'local>, &dyn SzEngine) -> Outcome<String>,
) -> JString<'local> {
    run(env, |env| {
        let engine = engine()?;
        let result = body(env, engine.as_ref())?;
        new_string(env, &result)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_primeEngine<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    run(&mut env, |_| Ok(engine()?.prime_engine()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getStats<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    text(&mut env, |_, engine| Ok(engine.get_stats()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_addRecord<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data_source_code: JString<'local>,
    record_id: JString<'local>,
    record_definition: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let data_source_code = string(env, &data_source_code)?;
        let record_id = string(env, &record_id)?;
        let record_definition = string(env, &record_definition)?;
        Ok(engine.add_record(
            &data_source_code,
            &record_id,
            &record_definition,
            flags(bits),
        )?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getRecordPreview<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    record_definition: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let record_definition = string(env, &record_definition)?;
        Ok(engine.get_record_preview(&record_definition, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_deleteRecord<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data_source_code: JString<'local>,
    record_id: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let data_source_code = string(env, &data_source_code)?;
        let record_id = string(env, &record_id)?;
        Ok(engine.delete_record(&data_source_code, &record_id, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_reevaluateRecord<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data_source_code: JString<'local>,
    record_id: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let data_source_code = string(env, &data_source_code)?;
        let record_id = string(env, &record_id)?;
        Ok(engine.reevaluate_record(&data_source_code, &record_id, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_reevaluateEntity<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    entity_id: jlong,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |_, engine| {
        Ok(engine.reevaluate_entity(entity_id, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_searchByAttributes<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    attributes: JString<'local>,
    search_profile: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let attributes = string(env, &attributes)?;
        let search_profile = optional_string(env, &search_profile)?;
        Ok(engine.search_by_attributes(&attributes, search_profile.as_deref(), flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_whySearch<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    attributes: JString<'local>,
    entity_id: jlong,
    search_profile: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let attributes = string(env, &attributes)?;
        let search_profile = optional_string(env, &search_profile)?;
        Ok(engine.why_search(
            &attributes,
            entity_id,
            search_profile.as_deref(),
            flags(bits),
        )?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getEntityById<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    entity_id: jlong,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |_, engine| {
        Ok(engine.get_entity(EntityRef::Id(entity_id), flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getEntityByRecord<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data_source_code: JString<'local>,
    record_id: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let data_source_code = string(env, &data_source_code)?;
        let record_id = string(env, &record_id)?;
        let entity = EntityRef::from_record(&data_source_code, &record_id);
        Ok(engine.get_entity(entity, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getRecord<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data_source_code: JString<'local>,
    record_id: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let data_source_code = string(env, &data_source_code)?;
        let record_id = string(env, &record_id)?;
        Ok(engine.get_record(&data_source_code, &record_id, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_findInterestingEntitiesById<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    entity_id: jlong,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |_, engine| {
        Ok(engine.find_interesting_entities(EntityRef::Id(entity_id), flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_findInterestingEntitiesByRecord<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data_source_code: JString<'local>,
    record_id: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let data_source_code = string(env, &data_source_code)?;
        let record_id = string(env, &record_id)?;
        let entity = EntityRef::from_record(&data_source_code, &record_id);
        Ok(engine.find_interesting_entities(entity, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_findPathById<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    start_entity_id: jlong,
    end_entity_id: jlong,
    max_degrees: jlong,
    avoid_entity_ids: JString<'local>,
    required_data_sources: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let avoid = optional_set::<i64>(env, &avoid_entity_ids)?;
        let required = optional_set::<String>(env, &required_data_sources)?;
        Ok(engine.find_path_by_entity_id(
            start_entity_id,
            end_entity_id,
            max_degrees,
            avoid.as_ref(),
            required.as_ref(),
            flags(bits),
        )?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_findPathByRecord<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    start_data_source_code: JString<'local>,
    start_record_id: JString<'local>,
    end_data_source_code: JString<'local>,
    end_record_id: JString<'local>,
    max_degrees: jlong,
    avoid_record_keys: JString<'local>,
    required_data_sources: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let start_data_source_code = string(env, &start_data_source_code)?;
        let start_record_id = string(env, &start_record_id)?;
        let end_data_source_code = string(env, &end_data_source_code)?;
        let end_record_id = string(env, &end_record_id)?;
        let avoid = optional_string(env, &avoid_record_keys)?
            .map(|text| record_keys(&text))
            .transpose()?;
        let avoid = avoid.as_deref().map(borrowed);
        let required = optional_set::<String>(env, &required_data_sources)?;
        Ok(engine.find_path_by_record_id(
            &start_data_source_code,
            &start_record_id,
            &end_data_source_code,
            &end_record_id,
            max_degrees,
            avoid.as_deref(),
            required.as_ref(),
            flags(bits),
        )?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_findNetworkById<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    entity_ids: JString<'local>,
    max_degrees: jlong,
    build_out_degrees: jlong,
    build_out_max_entities: jlong,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let entity_ids: Vec<i64> = json(&string(env, &entity_ids)?)?;
        Ok(engine.find_network_by_entity_id(
            &entity_ids,
            max_degrees,
            build_out_degrees,
            build_out_max_entities,
            flags(bits),
        )?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_findNetworkByRecord<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    keys: JString<'local>,
    max_degrees: jlong,
    build_out_degrees: jlong,
    build_out_max_entities: jlong,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let keys = record_keys(&string(env, &keys)?)?;
        Ok(engine.find_network_by_record_id(
            &borrowed(&keys),
            max_degrees,
            build_out_degrees,
            build_out_max_entities,
            flags(bits),
        )?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_whyRecordInEntity<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data_source_code: JString<'local>,
    record_id: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let data_source_code = string(env, &data_source_code)?;
        let record_id = string(env, &record_id)?;
        Ok(engine.why_record_in_entity(&data_source_code, &record_id, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_whyRecords<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    data_source_code1: JString<'local>,
    record_id1: JString<'local>,
    data_source_code2: JString<'local>,
    record_id2: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let data_source_code1 = string(env, &data_source_code1)?;
        let record_id1 = string(env, &record_id1)?;
        let data_source_code2 = string(env, &data_source_code2)?;
        let record_id2 = string(env, &record_id2)?;
        Ok(engine.why_records(
            &data_source_code1,
            &record_id1,
            &data_source_code2,
            &record_id2,
            flags(bits),
        )?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_whyEntities<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    entity_id1: jlong,
    entity_id2: jlong,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |_, engine| {
        Ok(engine.why_entities(entity_id1, entity_id2, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_howEntity<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    entity_id: jlong,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |_, engine| {
        Ok(engine.how_entity(entity_id, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getVirtualEntity<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    keys: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let keys = record_keys(&string(env, &keys)?)?;
        Ok(engine.get_virtual_entity(&keys, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_exportJsonEntityReport<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    bits: jlong,
) -> jlong {
    run(&mut env, |_| {
        Ok(engine()?.export_json_entity_report(flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_exportCsvEntityReport<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    csv_column_list: JString<'local>,
    bits: jlong,
) -> jlong {
    run(&mut env, |env| {
        let csv_column_list = string(env, &csv_column_list)?;
        Ok(engine()?.export_csv_entity_report(&csv_column_list, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_fetchNext<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    export_handle: jlong,
) -> JString<'local> {
    text(&mut env, |_, engine| Ok(engine.fetch_next(export_handle)?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_closeExportReport<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    export_handle: jlong,
) {
    run(&mut env, |_| {
        Ok(engine()?.close_export_report(export_handle)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_processRedoRecord<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    redo_record: JString<'local>,
    bits: jlong,
) -> JString<'local> {
    text(&mut env, |env, engine| {
        let redo_record = string(env, &redo_record)?;
        Ok(engine.process_redo_record(&redo_record, flags(bits))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getRedoRecord<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    text(&mut env, |_, engine| Ok(engine.get_redo_record()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_countRedoRecords<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jlong {
    run(&mut env, |_| Ok(engine()?.count_redo_records()?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_record_keys_decode_and_borrow() {
        let keys = record_keys(r#"[["CUSTOMERS","1001"],["WATCHLIST","1007"]]"#).unwrap();
        assert_eq!(
            borrowed(&keys),
            vec![("CUSTOMERS", "1001"), ("WATCHLIST", "1007")]
        );
        assert!(record_keys("[1]").is_err());
        assert_eq!(flags(5).unwrap().bits(), 5);
    }
}
