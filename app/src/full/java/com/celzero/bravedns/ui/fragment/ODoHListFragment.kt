/*
 * Copyright 2023 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.fragment

import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ODoHEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.databinding.DialogAddOdohRelayBinding
import com.celzero.bravedns.databinding.DialogSetCustomOdohBinding
import com.celzero.bravedns.databinding.FragmentOdohListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.DnsStamp
import com.celzero.bravedns.util.RecyclerViewSpacingDecoration
import com.celzero.bravedns.viewmodel.ODoHEndpointViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.net.MalformedURLException
import java.net.URL

class ODoHListFragment : Fragment(R.layout.fragment_odoh_list) {
    private val b by viewBinding(FragmentOdohListBinding::bind)

    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()

    private var layoutManager: RecyclerView.LayoutManager? = null
    private var adapter: ODoHEndpointAdapter? = null
    private val viewModel: ODoHEndpointViewModel by viewModel()

    companion object {
        fun newInstance() = ODoHListFragment()

        private val dpToPx: Float by lazy {
            Resources.getSystem().displayMetrics.density
        }

        private val spacing4dp: Int by lazy { (4 * dpToPx).toInt() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
        applyEdgeToEdge()
        renderRelays()
    }

    private fun initView() {
        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerOdoh.layoutManager = layoutManager
        b.recyclerOdoh.addItemDecoration(
            RecyclerViewSpacingDecoration(spacing4dp, spacing4dp)
        )

        adapter = ODoHEndpointAdapter(requireContext(), get())
        viewModel.dohEndpointList.observe(viewLifecycleOwner) {
            adapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.recyclerOdoh.adapter = adapter

        adapter!!.addLoadStateListener { loadStates ->
            val isEmpty = loadStates.source.refresh is LoadState.NotLoading &&
                    adapter!!.itemCount == 0
            val isLoading = loadStates.source.refresh is LoadState.Loading

            b.odohEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
            b.odohLoadingIndicator.visibility = if (isLoading && adapter!!.itemCount == 0) {
                View.VISIBLE
            } else {
                View.GONE
            }
            b.recyclerOdoh.visibility = if (isLoading && adapter!!.itemCount == 0) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }

    private fun initClickListeners() {
        // see CustomIpFragment#setupClickListeners#bringToFront()
        b.odohFabAdd.bringToFront()
        b.odohFabAdd.setOnClickListener { showAddDialog() }
        b.odohRelayAddBtn.setOnClickListener { showAddRelayDialog() }
    }

    private fun applyEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(b.recyclerOdoh) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                v.paddingBottom + systemBars.bottom
            )
            insets
        }
    }

    private fun showAddDialog() {
        val dialogBinding = DialogSetCustomOdohBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim).setView(dialogBinding.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        val heading = dialogBinding.dialogCustomUrlTop
        val applyURLBtn = dialogBinding.dialogCustomUrlOkBtn
        val cancelURLBtn = dialogBinding.dialogCustomUrlCancelBtn
        val customName = dialogBinding.dialogCustomNameEditText
        val customResolver = dialogBinding.dialogCustomResolverEditText
        val progressBar = dialogBinding.dialogCustomUrlLoading
        val errorTxt = dialogBinding.dialogCustomUrlFailureText

        val title =
            getString(
                R.string.two_argument_space,
                getString(R.string.lbl_add).replaceFirstChar(Char::uppercase),
                getString(R.string.lbl_odoh)
            )
        heading.text = title

        // fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        io {
            val nextIndex = appConfig.getODoHCount().plus(1)
            uiCtx {
                customName.setText(
                    getString(R.string.lbl_odoh) + nextIndex.toString(),
                    TextView.BufferType.EDITABLE
                )
            }
        }

        customName.setText(getString(R.string.lbl_odoh), TextView.BufferType.EDITABLE)
        applyURLBtn.setOnClickListener {
            val name = customName.text.toString()
            // the target may be given as an https:// URL or as an sdns:// stamp
            val resolver = resolveUrl(customResolver.text.toString())

            if (resolver != null) {
                insert(name, resolver)
                dialog.dismiss()
            } else {
                errorTxt.text = resources.getString(R.string.custom_url_error_invalid_url)
                errorTxt.visibility = View.VISIBLE
                cancelURLBtn.visibility = View.VISIBLE
                applyURLBtn.visibility = View.VISIBLE
                progressBar.visibility = View.INVISIBLE
            }
        }

        cancelURLBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // Returns the https URL for an sdns:// stamp or an https:// URL, or null if neither is valid.
    private fun resolveUrl(raw: String): String? {
        val text = raw.trim()
        return DnsStamp.toHttpsUrl(text) ?: text.takeIf { checkUrl(it) }
    }

    // Check that the URL is a plausible DOH server: https with a domain, a path (at least "/"),
    // and no query parameters or fragment.
    private fun checkUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            parsed.protocol == "https" &&
                parsed.host.isNotEmpty() &&
                parsed.path.isNotEmpty() &&
                parsed.query == null &&
                parsed.ref == null
        } catch (e: MalformedURLException) {
            false
        }
    }

    private fun insert(name: String, resolver: String) {
        io {
            var odohName: String = name
            if (name.isBlank()) {
                odohName = resolver
            }
            // relays are managed in the relay list; the selected relay is used for this target
            val endpoint =
                ODoHEndpoint(
                    id = 0,
                    odohName,
                    "",
                    resolver,
                    proxyIps = "",
                    desc = "",
                    isSelected = false,
                    isCustom = true,
                    modifiedDataTime = 0,
                    latency = 0
                )
            appConfig.insertODoHEndpoint(endpoint)
        }
    }

    // ---- relays -------------------------------------------------------------------------

    private fun relayList(): List<String> {
        return persistentState.odohRelays.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun saveRelays(relays: List<String>) {
        persistentState.odohRelays = relays.joinToString("\n")
    }

    private fun renderRelays() {
        val container = b.odohRelayList
        container.removeAllViews()
        val selected = persistentState.odohSelectedRelay
        relayList().forEach { url ->
            val row =
                LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_odoh_relay, container, false)
            val radio = row.findViewById<RadioButton>(R.id.item_relay_radio)
            val label = row.findViewById<TextView>(R.id.item_relay_url)
            val remove = row.findViewById<View>(R.id.item_relay_remove)
            label.text = url
            radio.isChecked = url == selected
            radio.setOnClickListener { selectRelay(url) }
            row.setOnClickListener { selectRelay(url) }
            remove.setOnClickListener { removeRelay(url) }
            container.addView(row)
        }
        b.odohRelayWarning.visibility = if (selected.isBlank()) View.VISIBLE else View.GONE
    }

    private fun selectRelay(url: String) {
        persistentState.odohSelectedRelay = url
        renderRelays()
        io { appConfig.onOdohRelayChanged() }
    }

    private fun addRelay(url: String) {
        val list = relayList()
        if (url !in list) saveRelays(list + url)
        // the first relay added is selected automatically
        if (persistentState.odohSelectedRelay.isBlank()) persistentState.odohSelectedRelay = url
        renderRelays()
        io { appConfig.onOdohRelayChanged() }
    }

    private fun removeRelay(url: String) {
        val remaining = relayList().filter { it != url }
        saveRelays(remaining)
        if (persistentState.odohSelectedRelay == url) {
            persistentState.odohSelectedRelay = remaining.firstOrNull() ?: ""
        }
        renderRelays()
        io { appConfig.onOdohRelayChanged() }
    }

    private fun showAddRelayDialog() {
        val db = DialogAddOdohRelayBinding.inflate(layoutInflater)
        val dialog =
            MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
                .setTitle(R.string.odoh_relay_dialog_title)
                .setView(db.root)
                .setPositiveButton(R.string.lbl_add, null)
                .setNegativeButton(R.string.lbl_cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = db.dialogRelayEditText.text.toString().trim()
                // a relay is given as an sdns:// stamp or an https:// URL, stored as https
                val relay = DnsStamp.toHttpsUrl(input) ?: input.takeIf { checkUrl(it) }
                if (relay == null) {
                    db.textInputLayoutRelay.error = getString(R.string.odoh_relay_invalid)
                } else {
                    db.textInputLayoutRelay.error = null
                    addRelay(relay)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
