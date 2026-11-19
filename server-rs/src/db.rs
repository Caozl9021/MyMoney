use std::str::FromStr;

use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use sqlx::{Executor, PgPool};

use crate::config::validate_schema_name;

pub const SCHEMA_SQL: &str = include_str!("../schema.sql");

pub async fn connect_pool(database_url: &str, schema: &str) -> Result<PgPool, String> {
    validate_schema_name(schema)?;
    let options =
        PgConnectOptions::from_str(database_url).map_err(|e| format!("数据库连接串无效: {}", e))?;
    let schema_name = schema.to_string();
    PgPoolOptions::new()
        .max_connections(16)
        .after_connect(move |conn, _meta| {
            let schema_copy = schema_name.clone();
            Box::pin(async move {
                let stmt = format!(r#"SET search_path TO "{}", public"#, schema_copy);
                conn.execute(stmt.as_str()).await?;
                Ok(())
            })
        })
        .connect_with(options)
        .await
        .map_err(|e| format!("连接 PostgreSQL 失败: {}", e))
}

pub fn render_schema_sql(schema: &str) -> Result<String, String> {
    validate_schema_name(schema)?;
    Ok(SCHEMA_SQL.replace("mymoney_home", schema))
}

/// 幂等应用 schema.sql 到指定 schema（替代 python -m app.migrate）。
pub async fn apply_schema(pool: &PgPool, schema: &str) -> Result<(), String> {
    let sql = render_schema_sql(schema)?;
    pool.execute(sql.as_str())
        .await
        .map_err(|e| format!("应用数据库 schema {} 失败: {}", schema, e))?;
    Ok(())
}
