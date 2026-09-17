// use std::env;
// use std::fs;
// use std::path::{Path, PathBuf};
// use std::process::Command;
// use std::sync::Arc;
// use std::time::Duration;

// use futures::executor::block_on;
// use tempfile::TempDir;
// use tokio::sync::Mutex;
// use tokio::time;
// use dotenv::dotenv;

// // Configuration struct to hold all environment variables
// pub struct Config {
//     pub git_user_name: String,
//     pub git_user_email: String,
//     pub remote_name: String,
//     pub remote_auth_type: String,
//     pub git_username: String,
//     pub git_password: String,
//     pub temp_dir_prefix: String,
//     pub local_repo_path: Option<String>,
//     pub origin_repo_path: Option<String>,
//     pub clone_repo_path: Option<String>,
// }

// impl Config {
//     // Create a new Config instance by loading values from environment variables
//     pub fn new() -> Self {
//         // Load environment variables from .env file
//         dotenv().ok();
        
//         Config {
//             git_user_name: env::var("GIT_USER_NAME").unwrap_or_else(|_| "Test User".to_string()),
//             git_user_email: env::var("GIT_USER_EMAIL").unwrap_or_else(|_| "test@example.com".to_string()),
//             remote_name: env::var("REMOTE_NAME").unwrap_or_else(|_| "origin".to_string()),
//             remote_auth_type: env::var("REMOTE_AUTH_TYPE").unwrap_or_else(|_| "HTTPS".to_string()),
//             git_username: env::var("GIT_USERNAME").unwrap_or_default(),
//             git_password: env::var("GIT_PASSWORD").unwrap_or_default(),
//             temp_dir_prefix: env::var("TEMP_DIR_PREFIX").unwrap_or_else(|_| "git_test_".to_string()),
//             local_repo_path: env::var("LOCAL_REPO_PATH").ok(),
//             origin_repo_path: env::var("ORIGIN_REPO_PATH").ok(),
//             clone_repo_path: env::var("CLONE_REPO_PATH").ok(),
//         }
//     }
    
//     // Get user credentials as a tuple
//     pub fn get_credentials(&self) -> (String, String) {
//         (self.git_username.clone(), self.git_password.clone())
//     }
    
//     // Get git user info as a tuple
//     pub fn get_user_info(&self) -> (String, String) {
//         (self.git_user_name.clone(), self.git_user_email.clone())
//     }
// }


// // Mock implementation for DartFnFuture
// struct DartFnFuture;

// impl DartFnFuture {
//     fn new() -> Self {
//         DartFnFuture {}
//     }
// }

// // Mock callback functions
// async fn mock_callback() {}
// async fn mock_log(_log_type: crate::LogType, _message: String) {}

// // Helper function to setup test repositories
// fn setup_test_repos() -> (PathBuf, PathBuf, TempDir) {
//     // Load configuration
//     let config = Config::new();
    
//     // Create a temporary directory for our test repositories
//     let temp_dir = tempfile::tempdir().unwrap();
    
//     // Use configured paths or generate new ones
//     let origin_path = match config.origin_repo_path {
//         Some(path) => PathBuf::from(path),
//         None => temp_dir.path().join("origin"),
//     };
    
//     let local_path = match config.local_repo_path {
//         Some(path) => PathBuf::from(path),
//         None => temp_dir.path().join("local"),
//     };
    
//     let clone_path = match config.clone_repo_path {
//         Some(path) => PathBuf::from(path),
//         None => temp_dir.path().join("clone"),
//     };
    
//     // Create directories
//     fs::create_dir_all(&origin_path).unwrap();
//     fs::create_dir_all(&local_path).unwrap();
    
//     // Initialize origin as a bare repository
//     Command::new("git")
//         .args(&["init", "--bare"])
//         .current_dir(&origin_path)
//         .output()
//         .expect("Failed to initialize origin repo");
    
//     // Initialize local repo
//     Command::new("git")
//         .args(&["init"])
//         .current_dir(&local_path)
//         .output()
//         .expect("Failed to initialize local repo");
    
//     // Configure git user
//     Command::new("git")
//         .args(&["config", "user.name", &config.git_user_name])
//         .current_dir(&local_path)
//         .output()
//         .expect("Failed to configure git user name");
    
//     Command::new("git")
//         .args(&["config", "user.email", &config.git_user_email])
//         .current_dir(&local_path)
//         .output()
//         .expect("Failed to configure git user email");
    
//     // Add origin as remote
//     Command::new("git")
//         .args(&["remote", "add", &config.remote_name, origin_path.to_str().unwrap()])
//         .current_dir(&local_path)
//         .output()
//         .expect("Failed to add remote");
    
//     // Create and commit an initial file
//     fs::write(local_path.join("initial.txt"), "Initial content").unwrap();
    
//     Command::new("git")
//         .args(&["add", "initial.txt"])
//         .current_dir(&local_path)
//         .output()
//         .expect("Failed to add initial file");
    
//     Command::new("git")
//         .args(&["commit", "-m", "Initial commit"])
//         .current_dir(&local_path)
//         .output()
//         .expect("Failed to commit initial file");
    
//     // Push to origin
//     Command::new("git")
//         .args(&["push", "-u", &config.remote_name, "main"])
//         .current_dir(&local_path)
//         .output()
//         .expect("Failed to push initial commit");
    
//     // Clone from origin
//     Command::new("git")
//         .args(&["clone", origin_path.to_str().unwrap(), clone_path.to_str().unwrap()])
//         .output()
//         .expect("Failed to clone repository");
    
//     // Configure git user in clone
//     Command::new("git")
//         .args(&["config", "user.name", &config.git_user_name])
//         .current_dir(&clone_path)
//         .output()
//         .expect("Failed to configure git user name in clone");
    
//     Command::new("git")
//         .args(&["config", "user.email", &config.git_user_email])
//         .current_dir(&clone_path)
//         .output()
//         .expect("Failed to configure git user email in clone");
    
//     (local_path, clone_path, temp_dir)
// }

// #[cfg(test)]
// mod tests {
//     use super::*;
//     use std::thread;
    
//     use crate::{
//         clone_repository, download_changes, force_pull, force_push, get_branch_name,
//         get_conflicting, get_uncommitted_file_paths, upload_changes, LogType,
//     };

//     #[test]
//     fn test_sync_push_changes_file_edit() {
//         let (local_path, clone_path, _temp_dir) = setup_test_repos();
        
//         // Edit a file in local repo
//         fs::write(local_path.join("initial.txt"), "Modified content").unwrap();
        
//         // Push changes
//         let result = block_on(upload_changes(
//             local_path.to_str().unwrap().to_string(),
//             "origin".to_string(),
//             "HTTPS".to_string(),
//             ("".to_string(), "".to_string()),
//             ("Test User".to_string(), "test@example.com".to_string()),
//             mock_callback,
//             mock_callback,
//             None,
//             "Modified initial file".to_string(),
//             mock_log,
//         ));
        
//         assert!(result.is_ok());
//         assert_eq!(result.unwrap(), Some(true));
        
//         // Pull in clone to verify changes were pushed
//         Command::new("git")
//             .args(&["pull"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to pull changes");
        
//         // Verify file was modified in clone
//         let content = fs::read_to_string(clone_path.join("initial.txt")).unwrap();
//         assert_eq!(content, "Modified content");
//     }
    
//     #[test]
//     fn test_sync_push_changes_file_create() {
//         let (local_path, clone_path, _temp_dir) = setup_test_repos();
        
//         // Create a new file in local repo
//         fs::write(local_path.join("new_file.txt"), "New file content").unwrap();
        
//         // Push changes
//         let result = block_on(upload_changes(
//             local_path.to_str().unwrap().to_string(),
//             "origin".to_string(),
//             "HTTPS".to_string(),
//             ("".to_string(), "".to_string()),
//             ("Test User".to_string(), "test@example.com".to_string()),
//             mock_callback,
//             mock_callback,
//             None,
//             "Added new file".to_string(),
//             mock_log,
//         ));
        
//         assert!(result.is_ok());
//         assert_eq!(result.unwrap(), Some(true));
        
//         // Pull in clone to verify changes were pushed
//         Command::new("git")
//             .args(&["pull"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to pull changes");
        
//         // Verify new file exists in clone
//         assert!(clone_path.join("new_file.txt").exists());
//         let content = fs::read_to_string(clone_path.join("new_file.txt")).unwrap();
//         assert_eq!(content, "New file content");
//     }
    
//     #[test]
//     fn test_sync_push_changes_file_delete() {
//         let (local_path, clone_path, _temp_dir) = setup_test_repos();
        
//         // Delete file in local repo
//         fs::remove_file(local_path.join("initial.txt")).unwrap();
        
//         // Push changes
//         let result = block_on(upload_changes(
//             local_path.to_str().unwrap().to_string(),
//             "origin".to_string(),
//             "HTTPS".to_string(),
//             ("".to_string(), "".to_string()),
//             ("Test User".to_string(), "test@example.com".to_string()),
//             mock_callback,
//             mock_callback,
//             None,
//             "Deleted initial file".to_string(),
//             mock_log,
//         ));
        
//         assert!(result.is_ok());
//         assert_eq!(result.unwrap(), Some(true));
        
//         // Pull in clone to verify changes were pushed
//         Command::new("git")
//             .args(&["pull"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to pull changes");
        
//         // Verify file was deleted in clone
//         assert!(!clone_path.join("initial.txt").exists());
//     }
    
//     #[test]
//     fn test_sync_push_changes_folder_create() {
//         let (local_path, clone_path, _temp_dir) = setup_test_repos();
        
//         // Create a new folder and file in local repo
//         fs::create_dir_all(local_path.join("new_folder")).unwrap();
//         fs::write(local_path.join("new_folder/file.txt"), "File in new folder").unwrap();
        
//         // Push changes
//         let result = block_on(upload_changes(
//             local_path.to_str().unwrap().to_string(),
//             "origin".to_string(),
//             "HTTPS".to_string(),
//             ("".to_string(), "".to_string()),
//             ("Test User".to_string(), "test@example.com".to_string()),
//             mock_callback,
//             mock_callback,
//             None,
//             "Added new folder with file".to_string(),
//             mock_log,
//         ));
        
//         assert!(result.is_ok());
//         assert_eq!(result.unwrap(), Some(true));
        
//         // Pull in clone to verify changes were pushed
//         Command::new("git")
//             .args(&["pull"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to pull changes");
        
//         // Verify folder and file exist in clone
//         assert!(clone_path.join("new_folder").exists());
//         assert!(clone_path.join("new_folder/file.txt").exists());
//         let content = fs::read_to_string(clone_path.join("new_folder/file.txt")).unwrap();
//         assert_eq!(content, "File in new folder");
//     }
    
//     #[test]
//     fn test_sync_push_changes_folder_delete() {
//         let (local_path, clone_path, _temp_dir) = setup_test_repos();
        
//         // Create a folder and file first
//         fs::create_dir_all(local_path.join("folder_to_delete")).unwrap();
//         fs::write(local_path.join("folder_to_delete/file.txt"), "File to delete").unwrap();
        
//         // Commit it
//         Command::new("git")
//             .args(&["add", "."])
//             .current_dir(&local_path)
//             .output()
//             .expect("Failed to add folder");
        
//         Command::new("git")
//             .args(&["commit", "-m", "Add folder to delete"])
//             .current_dir(&local_path)
//             .output()
//             .expect("Failed to commit folder");
        
//         Command::new("git")
//             .args(&["push"])
//             .current_dir(&local_path)
//             .output()
//             .expect("Failed to push folder");
        
//         // Pull in clone to get folder
//         Command::new("git")
//             .args(&["pull"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to pull folder");
        
//         // Now delete the folder
//         if cfg!(windows) {
//             fs::remove_file(local_path.join("folder_to_delete/file.txt")).unwrap();
//             fs::remove_dir(local_path.join("folder_to_delete")).unwrap();
//         } else {
//             fs::remove_dir_all(local_path.join("folder_to_delete")).unwrap();
//         }
        
//         // Push changes
//         let result = block_on(upload_changes(
//             local_path.to_str().unwrap().to_string(),
//             "origin".to_string(),
//             "HTTPS".to_string(),
//             ("".to_string(), "".to_string()),
//             ("Test User".to_string(), "test@example.com".to_string()),
//             mock_callback,
//             mock_callback,
//             None,
//             "Deleted folder".to_string(),
//             mock_log,
//         ));
        
//         assert!(result.is_ok());
//         assert_eq!(result.unwrap(), Some(true));
        
//         // Pull in clone to verify changes were pushed
//         Command::new("git")
//             .args(&["pull"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to pull changes");
        
//         // Verify folder was deleted in clone
//         assert!(!clone_path.join("folder_to_delete").exists());
//     }
    
//     #[test]
//     fn test_sync_pull_changes() {
//         let (local_path, clone_path, _temp_dir) = setup_test_repos();
        
//         // Make changes in clone repo
//         fs::write(clone_path.join("clone_file.txt"), "Content from clone").unwrap();
        
//         // Commit and push from clone
//         Command::new("git")
//             .args(&["add", "clone_file.txt"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to add file in clone");
        
//         Command::new("git")
//             .args(&["commit", "-m", "Added file from clone"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to commit file in clone");
        
//         Command::new("git")
//             .args(&["push"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to push from clone");
        
//         // Pull changes in local
//         let result = block_on(download_changes(
//             &local_path.to_str().unwrap().to_string(),
//             &"origin".to_string(),
//             &"HTTPS".to_string(),
//             &("".to_string(), "".to_string()),
//             &("Test User".to_string(), "test@example.com".to_string()),
//             mock_callback,
//             mock_log,
//         ));
        
//         assert!(result.is_ok());
//         assert_eq!(result.unwrap(), Some(true));
        
//         // Verify file exists in local
//         assert!(local_path.join("clone_file.txt").exists());
//         let content = fs::read_to_string(local_path.join("clone_file.txt")).unwrap();
//         assert_eq!(content, "Content from clone");
//     }
    
//     #[test]
//     fn test_cause_merge_conflict() {
//         let (local_path, clone_path, _temp_dir) = setup_test_repos();
        
//         // Edit the same file with different content in both repos
//         fs::write(local_path.join("initial.txt"), "Local change").unwrap();
//         fs::write(clone_path.join("initial.txt"), "Clone change").unwrap();
        
//         // Commit and push from clone first
//         Command::new("git")
//             .args(&["add", "initial.txt"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to add file in clone");
        
//         Command::new("git")
//             .args(&["commit", "-m", "Changed file in clone"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to commit file in clone");
        
//         Command::new("git")
//             .args(&["push"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to push from clone");
        
//         // Now try to push from local, it should detect a merge conflict
//         let result = block_on(upload_changes(
//             local_path.to_str().unwrap().to_string(),
//             "origin".to_string(),
//             "HTTPS".to_string(),
//             ("".to_string(), "".to_string()),
//             ("Test User".to_string(), "test@example.com".to_string()),
//             mock_callback,
//             mock_callback,
//             None,
//             "Changed file locally".to_string(),
//             mock_log,
//         ));
        
//         // Should fail or indicate there's a conflict
//         assert!(result.is_ok());
//         // This can either return Some(false) or indicate a merge conflict
        
//         // Check for conflicting files
//         let conflicts = block_on(get_conflicting(
//             &local_path.to_str().unwrap().to_string(),
//             mock_log,
//         ));
        
//         // There should be conflicts in initial.txt
//         assert!(conflicts.contains(&"initial.txt".to_string()) || 
//                 conflicts.iter().any(|path| path.contains("initial.txt")));
//     }
    
//     #[test]
//     fn test_force_pull_to_resolve_conflict() {
//         let (local_path, clone_path, _temp_dir) = setup_test_repos();
        
//         // Edit the same file with different content in both repos
//         fs::write(local_path.join("initial.txt"), "Local change").unwrap();
//         fs::write(clone_path.join("initial.txt"), "Clone change").unwrap();
        
//         // Commit and push from clone first
//         Command::new("git")
//             .args(&["add", "initial.txt"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to add file in clone");
        
//         Command::new("git")
//             .args(&["commit", "-m", "Changed file in clone"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to commit file in clone");
        
//         Command::new("git")
//             .args(&["push"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to push from clone");
        
//         // Now do a force pull from local to resolve conflict
//         let result = block_on(force_pull(
//             local_path.to_str().unwrap().to_string(),
//             "origin".to_string(),
//             "HTTPS".to_string(),
//             ("".to_string(), "".to_string()),
//             ("Test User".to_string(), "test@example.com".to_string()),
//             mock_log,
//         ));
        
//         assert!(result.is_ok());
        
//         // Verify local file now has clone content
//         let content = fs::read_to_string(local_path.join("initial.txt")).unwrap();
//         assert_eq!(content, "Clone change");
//     }
    
//     #[test]
//     fn test_force_push_to_resolve_conflict() {
//         let (local_path, clone_path, _temp_dir) = setup_test_repos();
        
//         // Edit the same file with different content in both repos
//         fs::write(local_path.join("initial.txt"), "Local change that will win").unwrap();
//         fs::write(clone_path.join("initial.txt"), "Clone change that will lose").unwrap();
        
//         // Commit and push from clone first
//         Command::new("git")
//             .args(&["add", "initial.txt"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to add file in clone");
        
//         Command::new("git")
//             .args(&["commit", "-m", "Changed file in clone"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to commit file in clone");
        
//         Command::new("git")
//             .args(&["push"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to push from clone");
        
//         // Now do a force push from local to override remote
//         let result = block_on(force_push(
//             local_path.to_str().unwrap().to_string(),
//             "origin".to_string(),
//             "HTTPS".to_string(),
//             ("".to_string(), "".to_string()),
//             ("Test User".to_string(), "test@example.com".to_string()),
//             "Force push to override remote".to_string(),
//             mock_log,
//         ));
        
//         assert!(result.is_ok());
        
//         // Pull in clone to verify changes were overwritten
//         Command::new("git")
//             .args(&["fetch", "--all"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to fetch changes");
        
//         Command::new("git")
//             .args(&["reset", "--hard", "origin/main"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to reset to origin/main");
        
//         // Verify clone file now has local content
//         let content = fs::read_to_string(clone_path.join("initial.txt")).unwrap();
//         assert_eq!(content, "Local change that will win");
//     }
    
//     #[test]
//     fn test_manual_sync() {
//         let (local_path, clone_path, _temp_dir) = setup_test_repos();
        
//         // Add a file with specific path in local repo
//         fs::write(local_path.join("manual_sync.txt"), "Manual sync content").unwrap();
        
//         // Push changes with specific file path
//         let result = block_on(upload_changes(
//             local_path.to_str().unwrap().to_string(),
//             "origin".to_string(),
//             "HTTPS".to_string(),
//             ("".to_string(), "".to_string()),
//             ("Test User".to_string(), "test@example.com".to_string()),
//             mock_callback,
//             mock_callback,
//             Some(vec!["manual_sync.txt".to_string()]),
//             "Manual sync of specific file".to_string(),
//             mock_log,
//         ));
        
//         assert!(result.is_ok());
//         assert_eq!(result.unwrap(), Some(true));
        
//         // Pull in clone to verify changes were pushed
//         Command::new("git")
//             .args(&["pull"])
//             .current_dir(&clone_path)
//             .output()
//             .expect("Failed to pull changes");
        
//         // Verify file exists in clone
//         assert!(clone_path.join("manual_sync.txt").exists());
//         let content = fs::read_to_string(clone_path.join("manual_sync.txt")).unwrap();
//         assert_eq!(content, "Manual sync content");
//     }
    
//     #[test]
//     fn test_get_uncommitted_file_paths() {
//         let (local_path, _clone_path, _temp_dir) = setup_test_repos();
        
//         // Create a new file
//         fs::write(local_path.join("uncommitted.txt"), "Uncommitted content").unwrap();
        
//         // Modify existing file
//         fs::write(local_path.join("initial.txt"), "Modified content").unwrap();
        
//         // Delete file and create folder
//         fs::remove_file(local_path.join("initial.txt")).unwrap();
//         fs::create_dir_all(local_path.join("new_folder")).unwrap();
//         fs::write(local_path.join("new_folder/nested_file.txt"), "Nested content").unwrap();
        
//         // Get uncommitted changes
//         let uncommitted = block_on(get_uncommitted_file_paths(
//             local_path.to_str().unwrap(),
//             mock_log,
//         ));
        
//         // Check for expected paths
//         assert!(uncommitted.iter().any(|(path, _)| path == "uncommitted.txt"));
//         assert!(uncommitted.iter().any(|(path, _)| path == "initial.txt"));
//         assert!(uncommitted.iter().any(|(path, _)| path == "new_folder/nested_file.txt"));
        
//         // Check for expected status codes
//         let new_file = uncommitted.iter().find(|(path, _)| path == "uncommitted.txt");
//         assert!(new_file.is_some());
//         assert_eq!(new_file.unwrap().1, 3);  // Addition
        
//         let deleted_file = uncommitted.iter().find(|(path, _)| path == "initial.txt");
//         assert!(deleted_file.is_some());
//         assert_eq!(deleted_file.unwrap().1, 2);  // Deletion
//     }
    
//     #[test]
//     fn test_get_branch_name() {
//         let (local_path, _clone_path, _temp_dir) = setup_test_repos();
        
//         // Get branch name
//         let branch = block_on(get_branch_name(
//             &local_path.to_str().unwrap().to_string(),
//             mock_log,
//         ));
        
//         // Should be "main" or "master" depending on git version
//         assert!(branch.is_some());
//         assert!(branch.unwrap() == "main" || branch.unwrap() == "master");
//     }
    
//     #[test]
//     fn test_clone_repository() {
//         let (_local_path, _clone_path, temp_dir) = setup_test_repos();
        
//         // Create path for a new clone
//         let new_clone_path = temp_dir.path().join("new_clone");
        
//         // Clone the repository
//         let result = block_on(clone_repository(
//             format!("{}", temp_dir.path().join("origin").to_str().unwrap()),
//             new_clone_path.to_str().unwrap().to_string(),
//             "HTTPS".to_string(),
//             ("".to_string(), "".to_string()),
//             ("Test User".to_string(), "test@example.com".to_string()),
//             |_| async { () },
//             |_| async { () },
//             mock_log,
//         ));
        
//         assert!(result.is_ok());
        
//         // Verify clone was successful by checking for initial file
//         assert!(new_clone_path.join("initial.txt").exists());
//     }
// }