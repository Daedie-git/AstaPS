import unittest
from capability_inventory import active_annotations


class ActiveInventoryTest(unittest.TestCase):
    def test_commented_annotations_are_not_capabilities(self):
        source = '''// @Opcodes(PacketOpcodes.Legacy)
/* @Opcodes(PacketOpcodes.Old) */
@Opcodes(PacketOpcodes.Current)
class Example {}'''
        self.assertEqual(["PacketOpcodes.Current"], active_annotations(source))

    def test_strings_and_disabled_annotations_remain_distinct(self):
        source = '''String text = "// @Opcodes(PacketOpcodes.String)";
@Opcodes(value = PacketOpcodes.Current, disabled = true)'''
        self.assertEqual(["value = PacketOpcodes.Current, disabled = true"], active_annotations(source))


if __name__ == "__main__":
    unittest.main()
