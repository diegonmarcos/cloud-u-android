package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import static androidx.room.ForeignKey.CASCADE;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

// The indexed read model of message.label_ids: one row per (message, label
// folder) membership. label_ids stays the source of truth on the message row;
// this table exists only so a folder can seek its labelled messages by index
// instead of a LIKE scan over every message.
@Entity(
        tableName = EntityMessageLabel.TABLE_NAME,
        primaryKeys = {"message", "folder"},
        foreignKeys = {
                @ForeignKey(childColumns = "message", entity = EntityMessage.class, parentColumns = "id", onDelete = CASCADE),
                @ForeignKey(childColumns = "folder", entity = EntityFolder.class, parentColumns = "id", onDelete = CASCADE)
        },
        indices = {
                @Index(value = {"message"}),
                @Index(value = {"folder"})
        }
)
public class EntityMessageLabel {
    static final String TABLE_NAME = "message_label";

    @NonNull
    public Long message;

    @NonNull
    public Long folder;
}
