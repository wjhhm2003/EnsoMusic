package com.enso.music.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.Window
import androidx.recyclerview.widget.LinearLayoutManager
import com.enso.music.databinding.DialogFolderPickerBinding
import com.enso.music.ui.adapter.FolderPickerAdapter
import java.io.File

class FolderPickerDialog(
    context: Context,
    private val onFolderSelected: (folderPath: String) -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogFolderPickerBinding
    private lateinit var adapter: FolderPickerAdapter
    private var currentDirectory: File = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
    private val rootPath: String = currentDirectory.absolutePath

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        setupRecyclerView()
        setupListeners()
        loadDirectory(currentDirectory)
    }

    private fun setupRecyclerView() {
        adapter = FolderPickerAdapter { subDir ->
            loadDirectory(subDir)
        }
        binding.rvFolderPicker.layoutManager = LinearLayoutManager(context)
        binding.rvFolderPicker.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnFolderUp.setOnClickListener {
            val parent = currentDirectory.parentFile
            if (parent != null && parent.exists() && parent.canRead()) {
                loadDirectory(parent)
            }
        }

        binding.btnConfirmFolder.setOnClickListener {
            onFolderSelected(currentDirectory.absolutePath)
            dismiss()
        }
    }

    private fun loadDirectory(dir: File) {
        currentDirectory = dir
        binding.tvCurrentPath.text = dir.absolutePath

        val files = dir.listFiles { file ->
            file.isDirectory && !file.name.startsWith(".")
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

        adapter.setFolders(files)
    }
}
