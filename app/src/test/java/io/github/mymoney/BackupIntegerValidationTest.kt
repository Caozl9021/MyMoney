package io.github.mymoney

import io.github.mymoney.data.Backup
import org.junit.Test

class BackupIntegerValidationTest {
    @Test(expected = IllegalArgumentException::class)
    fun fractionalCentsCannotBeSilentlyTruncatedDuringRestore() {
        Backup.decode("""{"kind":"MyMoneyBackup","format":2,"bills":[{"p":19.99,"t":1700000000000}]}""")
    }
    @Test(expected = IllegalArgumentException::class)
    fun numericStringsCannotBeTreatedAsZeroOrMissing() {
        Backup.decode("""{"kind":"MyMoneyBackup","format":2,"bills":[{"p":"1999","t":1700000000000}]}""")
    }
}
