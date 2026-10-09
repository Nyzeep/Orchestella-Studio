package io.github.nyzeep.orchestella

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.nyzeep.orchestella.runtime.contract.Protocol
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager

/**
 * Material 3 bottom sheet for managing instance dynamic port forwarding rules (Phase 4.8 Task 3).
 *
 * <p>Integrates {@link PortForwardingPresenter} and {@link PortForwardingRuleValidator}:
 * <ul>
 *   <li>Visualizes active TCP/UDP forwarding rules;</li>
 *   <li>Provides add rule form with real-time port range and collision verification;</li>
 *   <li>Supports quick enable/disable toggle and immediate rule deletion;</li>
 *   <li>Guarantees ergonomic touch targets (≥ 48x48 dp) and Material 3 design compliance.</li>
 * </ul>
 */
class PortForwardingBottomSheet(
    private val context: Context,
    private val instanceId: String,
    private val manager: PortForwardingManager
) : PortForwardingPresenter.View {

    private val presenter = PortForwardingPresenter(instanceId, manager)
    private var dialog: BottomSheetDialog? = null

    private var containerRulesList: LinearLayout? = null
    private var tvNoRulesHint: TextView? = null
    private var tilHostPort: TextInputLayout? = null
    private var etHostPort: TextInputEditText? = null
    private var tilGuestPort: TextInputLayout? = null
    private var etGuestPort: TextInputEditText? = null
    private var rbTcp: RadioButton? = null
    private var etDescription: TextInputEditText? = null

    fun show() {
        val bottomSheet = BottomSheetDialog(context)
        this.dialog = bottomSheet
        val view = LayoutInflater.from(context).inflate(R.layout.layout_port_forwarding_sheet, null)
        bottomSheet.setContentView(view)

        // Bind header
        val tvSubtitle = view.findViewById<TextView>(R.id.port_sheet_subtitle)
        tvSubtitle.text = "实例 $instanceId · 动态端口映射规则"

        // Bind list container
        containerRulesList = view.findViewById(R.id.container_rules_list)
        tvNoRulesHint = view.findViewById(R.id.tv_no_rules_hint)

        // Bind form
        tilHostPort = view.findViewById(R.id.til_host_port)
        etHostPort = view.findViewById(R.id.et_host_port)
        tilGuestPort = view.findViewById(R.id.til_guest_port)
        etGuestPort = view.findViewById(R.id.et_guest_port)
        rbTcp = view.findViewById(R.id.rb_tcp)
        etDescription = view.findViewById(R.id.et_description)

        val btnAddRule = view.findViewById<MaterialButton>(R.id.btn_add_rule)
        btnAddRule.setOnClickListener {
            tilHostPort?.error = null
            tilGuestPort?.error = null

            val hostPortText = etHostPort?.text?.toString().orEmpty()
            val guestPortText = etGuestPort?.text?.toString().orEmpty()
            val protocol = if (rbTcp?.isChecked == true) Protocol.TCP else Protocol.UDP
            val desc = etDescription?.text?.toString().orEmpty()

            presenter.addRule(hostPortText, guestPortText, protocol, desc)
        }

        bottomSheet.setOnDismissListener {
            presenter.detachView()
        }

        presenter.attachView(this)
        presenter.loadRules()

        bottomSheet.show()
    }

    override fun displayRules(rules: List<PortForwardingPresenter.RuleItemModel>) {
        val container = containerRulesList ?: return
        val hint = tvNoRulesHint ?: return

        container.removeAllViews()
        if (rules.isEmpty()) {
            hint.visibility = View.VISIBLE
            return
        }
        hint.visibility = View.GONE

        val inflater = LayoutInflater.from(context)
        for (rule in rules) {
            val itemView = inflater.inflate(R.layout.item_port_forwarding_rule, container, false)
            val tvProtocol = itemView.findViewById<TextView>(R.id.tv_rule_protocol)
            val tvPorts = itemView.findViewById<TextView>(R.id.tv_rule_ports)
            val tvDesc = itemView.findViewById<TextView>(R.id.tv_rule_desc)
            val switchEnabled = itemView.findViewById<MaterialSwitch>(R.id.switch_rule_enabled)
            val btnDelete = itemView.findViewById<MaterialButton>(R.id.btn_rule_delete)

            tvProtocol.text = rule.protocol.name
            tvPorts.text = "${rule.hostPort} ➔ ${rule.guestPort}"

            if (rule.description.isNotEmpty()) {
                tvDesc.text = rule.description
                tvDesc.visibility = View.VISIBLE
            } else {
                tvDesc.visibility = View.GONE
            }

            switchEnabled.isChecked = rule.isEnabled
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                presenter.toggleRule(rule.id, isChecked)
            }

            btnDelete.setOnClickListener {
                presenter.deleteRule(rule.id)
            }

            container.addView(itemView)
        }
    }

    override fun showValidationError(message: String?) {
        val msg = message ?: "输入错误"
        if (msg.contains("Host port", ignoreCase = true) || msg.contains("1024", ignoreCase = true)) {
            tilHostPort?.error = msg
        } else if (msg.contains("Guest port", ignoreCase = true)) {
            tilGuestPort?.error = msg
        } else {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun clearInputForm() {
        etHostPort?.setText("")
        etGuestPort?.setText("")
        etDescription?.setText("")
        tilHostPort?.error = null
        tilGuestPort?.error = null
    }

    override fun showSuccess(message: String?) {
        if (!message.isNullOrEmpty()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun showError(message: String?) {
        if (!message.isNullOrEmpty()) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
