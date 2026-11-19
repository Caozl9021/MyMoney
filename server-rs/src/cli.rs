use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;

use clap::{Parser, Subcommand};
use serde_json::{json, Value};

use crate::backup::storage::{audit_files, recycle_pending_backups};
use crate::config::{load_env_file_defaults, Settings};
use crate::db::{apply_schema, connect_pool};
use crate::state::{build_router, AppState};

#[derive(Parser)]
#[command(
    name = "mymoney-home",
    version,
    about = "麦米家庭同步、个人云备份与财务分析仪表盘单二进制服务"
)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Option<Commands>,
}

#[derive(Subcommand)]
pub enum Commands {
    /// 启动 HTTP 服务（默认）
    Serve {
        /// 可选环境文件路径；默认依次尝试当前目录及仓库 deploy/home-sync/.env
        #[arg(long)]
        env_file: Option<PathBuf>,
        #[arg(long, default_value = "0.0.0.0")]
        host: String,
        #[arg(long)]
        port: Option<u16>,
    },
    /// 幂等应用 schema.sql 到配置的 PostgreSQL schema
    Migrate {
        #[arg(long)]
        env_file: Option<PathBuf>,
    },
    /// 只读盘点备份文件卷与数据库索引一致性（绝不删除任何文件）
    BackupAudit {
        #[arg(long)]
        env_file: Option<PathBuf>,
    },
    /// 向运行中的服务请求一次性配对码
    PairCode {
        #[arg(long)]
        base_url: Option<String>,
        #[arg(long)]
        admin_token: Option<String>,
    },
    /// 向运行中的服务请求 5 分钟一次性 MMJOIN2 连接码
    ConnectCode {
        #[arg(long)]
        base_url: Option<String>,
        #[arg(long)]
        admin_token: Option<String>,
        #[arg(long)]
        ctrl_url: Option<String>,
        #[arg(long, default_value = "")]
        auth_key: String,
        #[arg(long)]
        server_url: Option<String>,
        #[arg(long, default_value = "")]
        device_name: String,
        #[arg(long, default_value = "")]
        reuse_code: String,
    },
}

fn load_env_if_present(explicit: Option<PathBuf>) {
    if let Some(p) = explicit {
        if let Err(e) = load_env_file_defaults(&p) {
            eprintln!("{}", e);
            std::process::exit(1);
        }
        return;
    }
    let candidates = [
        PathBuf::from(".env"),
        PathBuf::from("/Users/caozl/Project/Mymoney/deploy/home-sync/.env"),
        PathBuf::from("../deploy/home-sync/.env"),
    ];
    for c in candidates {
        if c.is_file() {
            let _ = load_env_file_defaults(&c);
            break;
        }
    }
}

pub async fn run_cli() {
    let cli = Cli::parse();
    let cmd = cli.command.unwrap_or(Commands::Serve {
        env_file: None,
        host: "0.0.0.0".to_string(),
        port: None,
    });

    match cmd {
        Commands::Serve {
            env_file,
            host,
            port,
        } => {
            load_env_if_present(env_file);
            let mut settings = match Settings::from_env() {
                Ok(s) => s,
                Err(e) => {
                    eprintln!("配置错误: {}", e);
                    std::process::exit(1);
                }
            };
            if let Some(p) = port {
                settings.port = p;
            }
            let pool = match connect_pool(&settings.database_url, &settings.schema).await {
                Ok(p) => p,
                Err(e) => {
                    eprintln!("{}", e);
                    std::process::exit(1);
                }
            };
            match recycle_pending_backups(&pool, &settings.backup_dir).await {
                Ok(summary) => {
                    if summary["pendingCount"].as_i64().unwrap_or(0) > 0 {
                        tracing::warn!(summary = %summary, "backup recycle still pending");
                    }
                }
                Err(e) => {
                    tracing::warn!(error = %e.message, "backup recycle sweep failed");
                }
            }

            let listen_port = settings.port;
            let state = AppState::new(settings, pool);
            let app = build_router(state);
            let addr: SocketAddr = format!("{}:{}", host, listen_port)
                .parse()
                .expect("valid bind address");
            tracing::info!(address = %addr, "mymoney-home listening");
            let listener = tokio::net::TcpListener::bind(addr)
                .await
                .expect("failed to bind address");
            axum::serve(listener, app)
                .with_graceful_shutdown(shutdown_signal())
                .await
                .expect("server error");
        }
        Commands::Migrate { env_file } => {
            load_env_if_present(env_file);
            let settings = Settings::from_env().unwrap_or_else(|e| {
                eprintln!("{}", e);
                std::process::exit(1);
            });
            let pool = connect_pool(&settings.database_url, &settings.schema)
                .await
                .unwrap_or_else(|e| {
                    eprintln!("{}", e);
                    std::process::exit(1);
                });
            apply_schema(&pool, &settings.schema)
                .await
                .unwrap_or_else(|e| {
                    eprintln!("{}", e);
                    std::process::exit(1);
                });
            println!("applied schema {}", settings.schema);
        }
        Commands::BackupAudit { env_file } => {
            load_env_if_present(env_file);
            let settings = Settings::from_env().unwrap_or_else(|e| {
                eprintln!("{}", e);
                std::process::exit(1);
            });
            let pool = connect_pool(&settings.database_url, &settings.schema)
                .await
                .unwrap_or_else(|e| {
                    eprintln!("{}", e);
                    std::process::exit(1);
                });
            let result = audit_files(&pool, &settings.backup_dir)
                .await
                .unwrap_or_else(|e| {
                    eprintln!("{}", e.message);
                    std::process::exit(1);
                });
            println!("{}", serde_json::to_string_pretty(&result).unwrap());
        }
        Commands::PairCode {
            base_url,
            admin_token,
        } => {
            load_env_if_present(None);
            let base = base_url
                .or_else(|| std::env::var("MYMONEY_BASE_URL").ok())
                .unwrap_or_else(|| "http://127.0.0.1:8080".to_string());
            let token = admin_token
                .or_else(|| std::env::var("MYMONEY_ADMIN_TOKEN").ok())
                .unwrap_or_default();
            let client = reqwest::Client::builder()
                .timeout(Duration::from_secs(10))
                .build()
                .unwrap();
            let resp = client
                .post(format!("{}/v1/admin/pair-code", base.trim_end_matches('/')))
                .header("X-Admin-Token", token)
                .send()
                .await
                .unwrap_or_else(|e| {
                    eprintln!("请求失败: {}", e);
                    std::process::exit(1);
                });
            let status = resp.status();
            let body: Value = resp.json().await.unwrap_or_else(|_| json!({}));
            if !status.is_success() {
                eprintln!("失败 ({}): {}", status, body);
                std::process::exit(1);
            }
            println!("{}", body["code"].as_str().unwrap_or(""));
        }
        Commands::ConnectCode {
            base_url,
            admin_token,
            ctrl_url,
            auth_key,
            server_url,
            device_name,
            reuse_code,
        } => {
            if !reuse_code.trim().is_empty() {
                eprintln!("云备份不支持复用业务连接码，请重新生成连接码");
                std::process::exit(1);
            }
            load_env_if_present(None);
            let base = base_url
                .or_else(|| std::env::var("MYMONEY_BASE_URL").ok())
                .unwrap_or_else(|| "http://127.0.0.1:8080".to_string());
            let token = admin_token
                .or_else(|| std::env::var("MYMONEY_ADMIN_TOKEN").ok())
                .unwrap_or_default();
            let ctrl = ctrl_url
                .or_else(|| std::env::var("MYMONEY_CTRL_URL").ok())
                .unwrap_or_else(|| "https://controlplane.tailscale.com".to_string());
            let server = server_url
                .or_else(|| std::env::var("MYMONEY_SERVER_URL").ok())
                .unwrap_or_default();

            let client = reqwest::Client::builder()
                .timeout(Duration::from_secs(10))
                .build()
                .unwrap();
            let resp = client
                .post(format!(
                    "{}/v1/admin/connect-code",
                    base.trim_end_matches('/')
                ))
                .header("X-Admin-Token", token)
                .json(&json!({
                    "ctrlUrl": ctrl,
                    "authKey": auth_key,
                    "serverUrl": server,
                    "deviceName": device_name,
                }))
                .send()
                .await
                .unwrap_or_else(|e| {
                    eprintln!("请求失败: {}", e);
                    std::process::exit(1);
                });
            let status = resp.status();
            let body: Value = resp.json().await.unwrap_or_else(|_| json!({}));
            if !status.is_success() {
                eprintln!("失败 ({}): {}", status, body);
                std::process::exit(1);
            }
            println!("{}", body["connectCode"].as_str().unwrap_or(""));
        }
    }
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        let mut sig = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("install SIGTERM handler");
        sig.recv().await;
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
