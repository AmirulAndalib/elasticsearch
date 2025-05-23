---
mapped_pages:
  - https://www.elastic.co/guide/en/elasticsearch/reference/current/elasticsearch-keystore.html
---

# elasticsearch-keystore [elasticsearch-keystore]

The `elasticsearch-keystore` command manages [secure settings](docs-content://deploy-manage/security/secure-settings.md) in the {{es}} keystore.


## Synopsis [elasticsearch-keystore-synopsis]

```shell
bin/elasticsearch-keystore
( [add <settings>] [-f] [--stdin]
| [add-file (<setting> <path>)+]
| [create] [-p]
| [has-passwd]
| [list]
| [passwd]
| [remove <setting>]
| [show [-o <output-file>] <setting>]
| [upgrade]
) [-h, --help] ([-s, --silent] | [-v, --verbose])
```


## Description [elasticsearch-keystore-description]

::::{important}
This command should be run as the user that will run {{es}}.
::::


Currently, all secure settings are node-specific settings that must have the same value on every node. Therefore you must run this command on every node.

When the keystore is password-protected, you must supply the password each time {{es}} starts.

Modifications to the keystore are not automatically applied to the running {{es}} node. Any changes to the keystore will take effect when you restart {{es}}. Some secure settings can be explicitly [reloaded](docs-content://deploy-manage/security/secure-settings.md#reloadable-secure-settings) without restart.

Only some settings are designed to be read from the keystore. However, there is no validation to block unsupported settings from the keystore and they can cause {{es}} to fail to start. To see whether a setting is supported in the keystore, see the setting reference.


## Parameters [elasticsearch-keystore-parameters]

`add <settings>`
:   Adds settings to the keystore. Multiple setting names can be specified as arguments to the `add` command. By default, you are prompted for the values of the settings. If the keystore is password protected, you are also prompted to enter the password. If a setting already exists in the keystore, you must confirm that you want to overwrite the current value. If the keystore does not exist, you must confirm that you want to create a keystore. To avoid these two confirmation prompts, use the `-f` parameter.

`add-file (<setting> <path>)+`
:   Adds files to the keystore.

`create`
:   Creates the keystore.

`-f, --force`
:   When used with the `add` parameter, the command no longer prompts you before overwriting existing entries in the keystore. Also, if you haven’t created a keystore yet, it creates a keystore that is obfuscated but not password protected.

`-h, --help`
:   Returns all of the command parameters.

`has-passwd`
:   Returns a success message if the keystore exists and is password-protected. Otherwise, the command fails with exit code 1 and returns an error message.

`list`
:   Lists the settings in the keystore. If the keystore is password protected, you are prompted to enter the password.

`-p`
:   When used with the `create` parameter, the command prompts you to enter a keystore password. If you don’t specify the `-p` flag or if you enter an empty password, the keystore is obfuscated but not password protected.

`passwd`
:   Changes or sets the keystore password. If the keystore is password protected, you are prompted to enter the current password and the new one. You can optionally use an empty string to remove the password. If the keystore is not password protected, you can use this command to set a password.

`remove <settings>`
:   Removes settings from the keystore. Multiple setting names can be specified as arguments to the `remove` command.

`show <setting>`
:   Displays the value of a single setting in the keystore. Pass the `-o` (or `--output`) parameter to write the setting to a file. If writing to the standard output (the terminal) the setting’s value is always interpreted as a UTF-8 string. If the setting contains binary data (for example for data that was added via the `add-file` command), always use the `-o` option to write to a file.

`-s, --silent`
:   Shows minimal output.

`-x, --stdin`
:   When used with the `add` parameter, you can pass the settings values through standard input (stdin). Separate multiple values with carriage returns or newlines. See [Add settings to the keystore](#add-string-to-keystore).

`upgrade`
:   Upgrades the internal format of the keystore.

`-v, --verbose`
:   Shows verbose output.

## Handling special characters

:::{important}
Improper handling of special characters can lead to authentication failures and service outages.
:::

**Exclamation mark**: `!`
: When using the shell, the combination `\!` is stored as just `!`. This can lead to authentication failures if the password is not stored as intended.

**Quotes**: `"`
: If quotes are used around the password, they become part of the password itself. This can cause the password to be incorrect when retrieved from the keystore.

**Backslash**: `\`
: The backslash character needs to be properly escaped. If not escaped correctly, it may be omitted or misinterpreted, leading to incorrect password storage.

## Examples [elasticsearch-keystore-examples]


### Create the keystore [creating-keystore]

To create the `elasticsearch.keystore`, use the `create` command:

```sh
bin/elasticsearch-keystore create -p
```

You are prompted to enter the keystore password. A password-protected `elasticsearch.keystore` file is created alongside the `elasticsearch.yml` file.


### Change the password of the keystore [changing-keystore-password]

To change the password of the `elasticsearch.keystore`, use the `passwd` command:

```sh
bin/elasticsearch-keystore passwd
```

If the {{es}} keystore is password protected, you are prompted to enter the current password and then enter the new one. If it is not password protected, you are prompted to set a password.


### List settings in the keystore [list-settings]

To list the settings in the keystore, use the `list` command.

```sh
bin/elasticsearch-keystore list
```

If the {{es}} keystore is password protected, you are prompted to enter the password.


### Add settings to the keystore [add-string-to-keystore]

Sensitive string settings, like authentication credentials for Cloud plugins, can be added with the `add` command:

```sh
bin/elasticsearch-keystore add the.setting.name.to.set
```

You are prompted to enter the value of the setting. If the {{es}} keystore is password protected, you are also prompted to enter the password.

You can also add multiple settings with the `add` command:

```sh
bin/elasticsearch-keystore add \
  the.setting.name.to.set \
  the.other.setting.name.to.set
```

You are prompted to enter the values of the settings. If the {{es}} keystore is password protected, you are also prompted to enter the password.

To pass the settings values through standard input (stdin), use the `--stdin` flag:

```sh
cat /file/containing/setting/value | bin/elasticsearch-keystore add --stdin the.setting.name.to.set
```
Alternatively, you can use the echo command to input the password into the keystore. For example, if the password is `your\!password` :

```sh
echo -n 'your\!password' | bin/elasticsearch-keystore add the.setting.name.to.set
```

Values for multiple settings must be separated by carriage returns or newlines.

### Add files to the keystore [add-file-to-keystore]

You can add sensitive files, like authentication key files for Cloud plugins, using the `add-file` command. Settings and file paths are specified in pairs consisting of `setting path`. The value of the setting will be the binary contents of the file path at the time the file is added to the keystore.

```sh
bin/elasticsearch-keystore add-file the.setting.name.to.set /path/example-file.json
```

You can add multiple files with the `add-file` command:

```sh
bin/elasticsearch-keystore add-file \
  the.setting.name.to.set /path/example-file.json \
  the.other.setting.name.to.set /path/other-example-file.json
```

If the {{es}} keystore is password protected, you are prompted to enter the password.


### Show settings in the keystore [show-keystore-value]

To display the value of a setting in the keystore use the `show` command:

```sh
bin/elasticsearch-keystore show the.name.of.the.setting.to.show
```

If the setting contains binary data you should write it to a file with the `-o` (or `--output`) option:

```sh
bin/elasticsearch-keystore show -o my_file binary.setting.name
```

If the {{es}} keystore is password protected, you are prompted to enter the password.


### Remove settings from the keystore [remove-settings]

To remove a setting from the keystore, use the `remove` command:

```sh
bin/elasticsearch-keystore remove the.setting.name.to.remove
```

You can also remove multiple settings with the `remove` command:

```sh
bin/elasticsearch-keystore remove \
  the.setting.name.to.remove \
  the.other.setting.name.to.remove
```

If the {{es}} keystore is password protected, you are prompted to enter the password.


### Upgrade the keystore [keystore-upgrade]

Occasionally, the internal format of the keystore changes. When {{es}} is installed from a package manager, an upgrade of the on-disk keystore to the new format is done during package upgrade. In other cases, {{es}} performs the upgrade during node startup. This requires that {{es}} has write permissions to the directory that contains the keystore. Alternatively, you can manually perform such an upgrade by using the `upgrade` command:

```sh
bin/elasticsearch-keystore upgrade
```

