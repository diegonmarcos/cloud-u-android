# Setting Up the Environment for the Test Suite

> [!IMPORTANT]
> All tests are written and optimised to run on a `Pixel 9 - API 35` emulated Android device

This test suite requires some preparation before running. Follow these steps to configure the environment variables correctly:

### Generate Screenshots Prerequisites

Setup Android

- Medium Phone API 35
- Medium Tablet API 35

Setup iOS

- iPhone 16 Pro Max
- iPad Air 13-inch

### Environment Variables

You need to set up the following environment variables to enable the tests for HTTPS and clone operations for GitHub and Gitea repositories.

#### Steps to Set Up:

1. **Copy the Template**:

   - Navigate to the `src` folder.
   - Locate the file named `env.rs.template`.
   - Make a copy of this file and rename it to `env.rs`.

2. **Edit the `env.rs` File**:
   Fill in the following variables with the appropriate values:

   - `MAESTRO_HTTPS_USERNAME`: Your HTTPS username (e.g., `ViscousTests`).
   - `MAESTRO_HTTPS_TOKEN`: Your HTTPS token for authentication.
   - `MAESTRO_GITHUB_USERNAME`: Your GitHub browser login username.
   - `MAESTRO_GITHUB_PASSWORD`: Your GitHub browser login password.
   - `MAESTRO_GITHUB_LIST_REPO_NAME`: The name of the GitHub repository to clone from the repo list (e.g., `TestObsidianVault`).
   - `MAESTRO_GITEA_LIST_REPO_NAME`: The name of the Gitea repository to clone from the repo list (e.g., `TestObsidianVault`).
   - `MAESTRO_GITHUB_CLONE_URL`: The HTTPS clone URL for your GitHub repository.
   - `MAESTRO_GITEA_CLONE_URL`: The HTTPS clone URL for your Gitea repository.
   - `MAESTRO_HTTPS_CLONE_URL`: The HTTPS clone URL for GitHub (used by HTTPS operations).
   - `MAESTRO_SSH_CLONE_URL`: The SSH clone URL for GitHub (used by SSH operations).

### Running the Test Suite

After setting up the environment variables, you can run the test suite using the following command:

```bash
cargo run
```

This command will set and use the environment variables you configured.

### Notes

- Ensure the repositories specified in the environment variables exist and you have the necessary permissions.
- The `repo` scope is required for the OAuth token to ensure proper access.

By following the above steps, you can successfully configure the environment and run the test suite. If you encounter any issues, verify that all variables are correctly set in the `env.rs` file.
