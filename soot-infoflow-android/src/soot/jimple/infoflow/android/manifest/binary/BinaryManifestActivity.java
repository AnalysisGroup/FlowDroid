package soot.jimple.infoflow.android.manifest.binary;

import soot.jimple.infoflow.android.axml.AXmlNode;
import soot.jimple.infoflow.android.manifest.IActivity;
import soot.jimple.infoflow.android.manifest.ProcessManifest;

/**
 * An activity loaded from a binary Android manifest
 * 
 * @author Steven Arzt
 *
 */
public class BinaryManifestActivity extends AbstractBinaryAndroidComponent implements IActivity {

	/**
	 * Creates a new instance of the {@link BinaryManifestActivity} class
	 * 
	 * @param node     The binary XML node
	 * @param manifest The manifest
	 */
	public BinaryManifestActivity(AXmlNode node, ProcessManifest manifest) {
		super(node, manifest);
	}

}
