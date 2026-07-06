package com.brikk.doris.sql

import com.intellij.lang.Commenter

class DorisCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "--"

    override fun getBlockCommentPrefix(): String = "/*"

    override fun getBlockCommentSuffix(): String = "*/"

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null
}
