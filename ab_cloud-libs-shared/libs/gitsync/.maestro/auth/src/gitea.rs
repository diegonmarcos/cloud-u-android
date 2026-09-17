use crate::Input;

pub fn get_input() -> Input {
    Input {
        before_all: vec![],
        before_each: vec![vec![
            "launch_notif_perm",
            "open_auth/positive",
            "gitea/start",
            "gitea/auth",
            "../clone/flows/assert",
            "../clone/flows/list/assert",
        ]],
        flows: vec![vec![vec![
            vec![
                "../clone/flows/list/gitea",
                "../clone/flows/select_folder_dialog/positive",
                "../clone/flows/select_folder/positive",
                "../clone/flows/select_folder/assert_not",
                "../clone/flows/select_folder_dialog/assert_not",
                "../home/flows/deselect_repo/positive",
            ],
            vec![
                "../clone/flows/url/gitea",
                "../clone/flows/select_folder_dialog/positive",
                "../clone/flows/select_folder/positive",
                "../clone/flows/select_folder/assert_not",
                "../clone/flows/select_folder_dialog/assert_not",
                "../home/flows/deselect_repo/positive",
            ],
            vec![
                "../clone/flows/local/positive",
                "../clone/flows/select_folder/neutral",
                "../clone/flows/select_folder/assert_not",
                "../clone/flows/select_folder_dialog/assert_not",
                "../home/flows/deselect_repo/positive",
            ],
        ]]],
    }
}
