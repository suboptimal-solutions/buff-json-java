package io.suboptimal.buffjson.internal;

/**
 * Correctly rounded conversion of a decimal {@code w * 10^q} to the nearest
 * IEEE-754 double, for a significand of up to 19 decimal digits: the
 * Eisel-Lemire algorithm (Lemire, "Number Parsing at a Gigabyte per Second",
 * 2021; with the analysis of Mushtak and Lemire, "Fast Number Parsing Without
 * Fallback", 2023, showing the 128-bit approximation below decides every
 * 19-digit input, so there is no slow path).
 *
 * <p>
 * The point is speed for the numbers {@code Double.toString} (and hence
 * {@code JsonFormat}, most JSON producers) prints: shortest round-trip forms of
 * 16-17 digits, which are outside the range where one exact double operation
 * suffices (Clinger) and would otherwise go through {@code Double.parseDouble},
 * which allocates a {@code String} and is an order of magnitude slower. The
 * result is bit-for-bit what {@code parseDouble} returns; the tests check that
 * against the JDK on millions of inputs.
 *
 * <p>
 * Public only so its tests can reach it from another package; it is an internal
 * class, not API.
 */
public final class FastDouble {

	private static final int SMALLEST_POWER_OF_TEN = -342;
	private static final int LARGEST_POWER_OF_TEN = 308;

	private static final long INFINITY_BITS = 0x7FF0000000000000L;

	private FastDouble() {
	}

	/**
	 * 128-bit approximations of the powers of five, {@code 2 * (q + 342)} holds the
	 * high word and the next element the low word, for {@code q} in
	 * {@code [-342, 308]}: for {@code q >= 0} {@code 5^q} shifted to have its top
	 * bit at bit 127 (truncated); for {@code q < 0} the same rounded up
	 * ({@code floor(2^k / 5^-q) + 1} for a suitable {@code k}). This is the table
	 * of the reference implementation; {@code FastDoubleTest} derives it again with
	 * {@link java.math.BigInteger} and compares. It is kept as text and decoded on
	 * first use, so that an application that never sees a long number pays nothing,
	 * and one that does pays a fraction of a millisecond rather than the ~25 ms
	 * that computing it takes.
	 */
	private static final class Powers {
		static final long[] FIVE = decode(TABLE);

		private static long[] decode(String hex) {
			long[] table = new long[hex.length() / 16];
			for (int i = 0; i < table.length; i++) {
				long v = 0;
				for (int k = 0; k < 16; k++) {
					v = (v << 4) | Character.digit(hex.charAt(i * 16 + k), 16);
				}
				table[i] = v;
			}
			return table;
		}
	}

	/** The powers of five, sixteen hex digits per word, high word first. */
	private static final String TABLE = "" + "eef453d6923bd65a113faa2906a13b3f" + "9558b4661b6565f84ac7ca59a424c507"
			+ "baaee17fa23ebf765d79bcf00d2df649" + "e95a99df8ace6f53f4d82c2c107973dc"
			+ "91d8a02bb6c1059479071b9b8a4be869" + "b64ec836a47146f99748e2826cdee284"
			+ "e3e27a444d8d98b7fd1b1b2308169b25" + "8e6d8c6ab0787f72fe30f0f5e50e20f7"
			+ "b208ef855c969f4fbdbd2d335e51a935" + "de8b2b66b3bc4723ad2c788035e61382"
			+ "8b16fb203055ac764c3bcb5021afcc31" + "addcb9e83c6b1793df4abe242a1bbf3d"
			+ "d953e8624b85dd78d71d6dad34a2af0d" + "87d4713d6f33aa6b8672648c40e5ad68"
			+ "a9c98d8ccb009506680efdaf511f18c2" + "d43bf0effdc0ba480212bd1b2566def2"
			+ "84a57695fe98746d014bb630f7604b57" + "a5ced43b7e3e9188419ea3bd35385e2d"
			+ "cf42894a5dce35ea52064cac828675b9" + "818995ce7aa0e1b27343efebd1940993"
			+ "a1ebfb4219491a1f1014ebe6c5f90bf8" + "ca66fa129f9b60a6d41a26e077774ef6"
			+ "fd00b897478238d08920b098955522b4" + "9e20735e8cb1638255b46e5f5d5535b0"
			+ "c5a890362fddbc62eb2189f734aa831d" + "f712b443bbd52b7ba5e9ec7501d523e4"
			+ "9a6bb0aa55653b2d47b233c92125366e" + "c1069cd4eabe89f8999ec0bb696e840a"
			+ "f148440a256e2c76c00670ea43ca250d" + "96cd2a865764dbca380406926a5e5728"
			+ "bc807527ed3e12bcc605083704f5ecf2" + "eba09271e88d976bf7864a44c633682e"
			+ "93445b8731587ea37ab3ee6afbe0211d" + "b8157268fdae9e4c5960ea05bad82964"
			+ "e61acf033d1a45df6fb92487298e33bd" + "8fd0c16206306baba5d3b6d479f8e056"
			+ "b3c4f1ba87bc86968f48a4899877186c" + "e0b62e2929aba83c331acdabfe94de87"
			+ "8c71dcd9ba0b49259ff0c08b7f1d0b14" + "af8e5410288e1b6f07ecf0ae5ee44dd9"
			+ "db71e91432b1a24ac9e82cd9f69d6150" + "892731ac9faf056ebe311c083a225cd2"
			+ "ab70fe17c79ac6ca6dbd630a48aaf406" + "d64d3d9db981787d092cbbccdad5b108"
			+ "85f0468293f0eb4e25bbf56008c58ea5" + "a76c582338ed2621af2af2b80af6f24e"
			+ "d1476e2c07286faa1af5af660db4aee1" + "82cca4db847945ca50d98d9fc890ed4d"
			+ "a37fce126597973ce50ff107bab528a0" + "cc5fc196fefd7d0c1e53ed49a96272c8"
			+ "ff77b1fcbebcdc4f25e8e89c13bb0f7a" + "9faacf3df73609b177b191618c54e9ac"
			+ "c795830d75038c1dd59df5b9ef6a2417" + "f97ae3d0d2446f254b0573286b44ad1d"
			+ "9becce62836ac5774ee367f9430aec32" + "c2e801fb244576d5229c41f793cda73f"
			+ "f3a20279ed56d48a6b43527578c1110f" + "9845418c345644d6830a13896b78aaa9"
			+ "be5691ef416bd60c23cc986bc656d553" + "edec366b11c6cb8f2cbfbe86b7ec8aa8"
			+ "94b3a202eb1c3f397bf7d71432f3d6a9" + "b9e08a83a5e34f07daf5ccd93fb0cc53"
			+ "e858ad248f5c22c9d1b3400f8f9cff68" + "91376c36d99995be23100809b9c21fa1"
			+ "b58547448ffffb2dabd40a0c2832a78a" + "e2e69915b3fff9f916c90c8f323f516c"
			+ "8dd01fad907ffc3bae3da7d97f6792e3" + "b1442798f49ffb4a99cd11cfdf41779c"
			+ "dd95317f31c7fa1d40405643d711d583" + "8a7d3eef7f1cfc52482835ea666b2572"
			+ "ad1c8eab5ee43b66da3243650005eecf" + "d863b256369d4a4090bed43e40076a82"
			+ "873e4f75e2224e685a7744a6e804a291" + "a90de3535aaae202711515d0a205cb36"
			+ "d3515c2831559a830d5a5b44ca873e03" + "8412d9991ed58091e858790afe9486c2"
			+ "a5178fff668ae0b6626e974dbe39a872" + "ce5d73ff402d98e3fb0a3d212dc8128f"
			+ "80fa687f881c7f8e7ce66634bc9d0b99" + "a139029f6a239f721c1fffc1ebc44e80"
			+ "c987434744ac874ea327ffb266b56220" + "fbe9141915d7a9224bf1ff9f0062baa8"
			+ "9d71ac8fada6c9b56f773fc3603db4a9" + "c4ce17b399107c22cb550fb4384d21d3"
			+ "f6019da07f549b2b7e2a53a146606a48" + "99c102844f94e0fb2eda7444cbfc426d"
			+ "c0314325637a1939fa911155fefb5308" + "f03d93eebc589f88793555ab7eba27ca"
			+ "96267c7535b763b54bc1558b2f3458de" + "bbb01b9283253ca29eb1aaedfb016f16"
			+ "ea9c227723ee8bcb465e15a979c1cadc" + "92a1958a7675175f0bfacd89ec191ec9"
			+ "b749faed14125d36cef980ec671f667b" + "e51c79a85916f48482b7e12780e7401a"
			+ "8f31cc0937ae58d2d1b2ecb8b0908810" + "b2fe3f0b8599ef07861fa7e6dcb4aa15"
			+ "dfbdcece67006ac967a791e093e1d49a" + "8bd6a141006042bde0c8bb2c5c6d24e0"
			+ "aecc49914078536d58fae9f773886e18" + "da7f5bf590966848af39a475506a899e"
			+ "888f99797a5e012d6d8406c952429603" + "aab37fd7d8f58178c8e5087ba6d33b83"
			+ "d5605fcdcf32e1d6fb1e4a9a90880a64" + "855c3be0a17fcd265cf2eea09a55067f"
			+ "a6b34ad8c9dfc06ff42faa48c0ea481e" + "d0601d8efc57b08bf13b94daf124da26"
			+ "823c12795db6ce5776c53d08d6b70858" + "a2cb1717b52481ed54768c4b0c64ca6e"
			+ "cb7ddcdda26da268a9942f5dcf7dfd09" + "fe5d54150b090b02d3f93b35435d7c4c"
			+ "9efa548d26e5a6e1c47bc5014a1a6daf" + "c6b8e9b0709f109a359ab6419ca1091b"
			+ "f867241c8cc6d4c0c30163d203c94b62" + "9b407691d7fc44f879e0de63425dcf1d"
			+ "c21094364dfb5636985915fc12f542e4" + "f294b943e17a2bc43e6f5b7b17b2939d"
			+ "979cf3ca6cec5b5aa705992ceecf9c42" + "bd8430bd0827723150c6ff782a838353"
			+ "ece53cec4a314ebda4f8bf5635246428" + "940f4613ae5ed136871b7795e136be99"
			+ "b913179899f6858428e2557b59846e3f" + "e757dd7ec07426e5331aeada2fe589cf"
			+ "9096ea6f3848984f3ff0d2c85def7621" + "b4bca50b065abe630fed077a756b53a9"
			+ "e1ebce4dc7f16dfbd3e8495912c62894" + "8d3360f09cf6e4bd64712dd7abbbd95c"
			+ "b080392cc4349decbd8d794d96aacfb3" + "dca04777f541c567ecf0d7a0fc5583a0"
			+ "89e42caaf9491b60f41686c49db57244" + "ac5d37d5b79b6239311c2875c522ced5"
			+ "d77485cb25823ac77d633293366b828b" + "86a8d39ef77164bcae5dff9c02033197"
			+ "a8530886b54dbdebd9f57f830283fdfc" + "d267caa862a12d66d072df63c324fd7b"
			+ "8380dea93da4bc604247cb9e59f71e6d" + "a46116538d0deb7852d9be85f074e608"
			+ "cd795be87051665667902e276c921f8b" + "806bd9714632dff600ba1cd8a3db53b6"
			+ "a086cfcd97bf97f380e8a40eccd228a4" + "c8a883c0fdaf7df06122cd128006b2cd"
			+ "fad2a4b13d1b5d6c796b805720085f81" + "9cc3a6eec6311a63cbe3303674053bb0"
			+ "c3f490aa77bd60fcbedbfc4411068a9c" + "f4f1b4d515acb93bee92fb5515482d44"
			+ "991711052d8bf3c5751bdd152d4d1c4a" + "bf5cd54678eef0b6d262d45a78a0635d"
			+ "ef340a98172aace486fb897116c87c34" + "9580869f0e7aac0ed45d35e6ae3d4da0"
			+ "bae0a846d21957128974836059cca109" + "e998d258869facd72bd1a438703fc94b"
			+ "91ff83775423cc067b6306a34627ddcf" + "b67f6455292cbf081a3bc84c17b1d542"
			+ "e41f3d6a7377eeca20caba5f1d9e4a93" + "8e938662882af53e547eb47b7282ee9c"
			+ "b23867fb2a35b28de99e619a4f23aa43" + "dec681f9f4c31f316405fa00e2ec94d4"
			+ "8b3c113c38f9f37ede83bc408dd3dd04" + "ae0b158b4738705e9624ab50b148d445"
			+ "d98ddaee19068c763badd624dd9b0957" + "87f8a8d4cfa417c9e54ca5d70a80e5d6"
			+ "a9f6d30a038d1dbc5e9fcf4ccd211f4c" + "d47487cc8470652b7647c3200069671f"
			+ "84c8d4dfd2c63f3b29ecd9f40041e073" + "a5fb0a17c777cf09f468107100525890"
			+ "cf79cc9db955c2cc7182148d4066eeb4" + "81ac1fe293d599bfc6f14cd848405530"
			+ "a21727db38cb002fb8ada00e5a506a7c" + "ca9cf1d206fdc03ba6d90811f0e4851c"
			+ "fd442e4688bd304a908f4a166d1da663" + "9e4a9cec15763e2e9a598e4e043287fe"
			+ "c5dd44271ad3cdba40eff1e1853f29fd" + "f7549530e188c128d12bee59e68ef47c"
			+ "9a94dd3e8cf578b982bb74f8301958ce" + "c13a148e3032d6e7e36a52363c1faf01"
			+ "f18899b1bc3f8ca1dc44e6c3cb279ac1" + "96f5600f15a7b7e529ab103a5ef8c0b9"
			+ "bcb2b812db11a5de7415d448f6b6f0e7" + "ebdf661791d60f56111b495b3464ad21"
			+ "936b9fcebb25c995cab10dd900beec34" + "b84687c269ef3bfb3d5d514f40eea742"
			+ "e65829b3046b0afa0cb4a5a3112a5112" + "8ff71a0fe2c2e6dc47f0e785eaba72ab"
			+ "b3f4e093db73a09359ed216765690f56" + "e0f218b8d25088b8306869c13ec3532c"
			+ "8c974f73837255731e414218c73a13fb" + "afbd2350644eeacfe5d1929ef90898fa"
			+ "dbac6c247d62a583df45f746b74abf39" + "894bc396ce5da7726b8bba8c328eb783"
			+ "ab9eb47c81f5114f066ea92f3f326564" + "d686619ba27255a2c80a537b0efefebd"
			+ "8613fd0145877585bd06742ce95f5f36" + "a798fc4196e952e72c48113823b73704"
			+ "d17f3b51fca3a7a0f75a15862ca504c5" + "82ef85133de648c49a984d73dbe722fb"
			+ "a3ab66580d5fdaf5c13e60d0d2e0ebba" + "cc963fee10b7d1b3318df905079926a8"
			+ "ffbbcfe994e5c61ffdf17746497f7052" + "9fd561f1fd0f9bd3feb6ea8bedefa633"
			+ "c7caba6e7c5382c8fe64a52ee96b8fc0" + "f9bd690a1b68637b3dfdce7aa3c673b0"
			+ "9c1661a651213e2d06bea10ca65c084e" + "c31bfa0fe5698db8486e494fcff30a62"
			+ "f3e2f893dec3f1265a89dba3c3efccfa" + "986ddb5c6b3a76b7f89629465a75e01c"
			+ "be89523386091465f6bbb397f1135823" + "ee2ba6c0678b597f746aa07ded582e2c"
			+ "94db483840b717efa8c2a44eb4571cdc" + "ba121a4650e4ddeb92f34d62616ce413"
			+ "e896a0d7e51e156677b020baf9c81d17" + "915e2486ef32cd600ace1474dc1d122e"
			+ "b5b5ada8aaff80b80d819992132456ba" + "e3231912d5bf60e610e1fff697ed6c69"
			+ "8df5efabc5979c8fca8d3ffa1ef463c1" + "b1736b96b6fd83b3bd308ff8a6b17cb2"
			+ "ddd0467c64bce4a0ac7cb3f6d05ddbde" + "8aa22c0dbef60ee46bcdf07a423aa96b"
			+ "ad4ab7112eb3929d86c16c98d2c953c6" + "d89d64d57a607744e871c7bf077ba8b7"
			+ "87625f056c7c4a8b11471cd764ad4972" + "a93af6c6c79b5d2dd598e40d3dd89bcf"
			+ "d389b478798234794aff1d108d4ec2c3" + "843610cb4bf160cbcedf722a585139ba"
			+ "a54394fe1eedb8fec2974eb4ee658828" + "ce947a3da6a9273e733d226229feea32"
			+ "811ccc668829b8870806357d5a3f525f" + "a163ff802a3426a8ca07c2dcb0cf26f7"
			+ "c9bcff6034c13052fc89b393dd02f0b5" + "fc2c3f3841f17c67bbac2078d443ace2"
			+ "9d9ba7832936edc0d54b944b84aa4c0d" + "c5029163f384a9310a9e795e65d4df11"
			+ "f64335bcf065d37d4d4617b5ff4a16d5" + "99ea0196163fa42e504bced1bf8e4e45"
			+ "c06481fb9bcf8d39e45ec2862f71e1d6" + "f07da27a82c370885d767327bb4e5a4c"
			+ "964e858c91ba26553a6a07f8d510f86f" + "bbe226efb628afea890489f70a55368b"
			+ "eadab0aba3b2dbe52b45ac74ccea842e" + "92c8ae6b464fc96f3b0b8bc90012929d"
			+ "b77ada0617e3bbcb09ce6ebb40173744" + "e55990879ddcaabdcc420a6a101d0515"
			+ "8f57fa54c2a9eab69fa946824a12232d" + "b32df8e9f354656447939822dc96abf9"
			+ "dff9772470297ebd59787e2b93bc56f7" + "8bfbea76c619ef3657eb4edb3c55b65a"
			+ "aefae51477a06b03ede622920b6b23f1" + "dab99e59958885c4e95fab368e45eced"
			+ "88b402f7fd75539b11dbcb0218ebb414" + "aae103b5fcd2a881d652bdc29f26a119"
			+ "d59944a37c0752a24be76d3346f0495f" + "857fcae62d8493a56f70a4400c562ddb"
			+ "a6dfbd9fb8e5b88ecb4ccd500f6bb952" + "d097ad07a71f26b27e2000a41346a7a7"
			+ "825ecc24c873782f8ed400668c0c28c8" + "a2f67f2dfa90563b728900802f0f32fa"
			+ "cbb41ef979346bca4f2b40a03ad2ffb9" + "fea126b7d78186bce2f610c84987bfa8"
			+ "9f24b832e6b0f4360dd9ca7d2df4d7c9" + "c6ede63fa05d314391503d1c79720dbb"
			+ "f8a95fcf88747d9475a44c6397ce912a" + "9b69dbe1b548ce7cc986afbe3ee11aba"
			+ "c24452da229b021bfbe85badce996168" + "f2d56790ab41c2a2fae27299423fb9c3"
			+ "97c560ba6b0919a5dccd879fc967d41a" + "bdb6b8e905cb600f5400e987bbc1c920"
			+ "ed246723473e3813290123e9aab23b68" + "9436c0760c86e30bf9a0b6720aaf6521"
			+ "b94470938fa89bcef808e40e8d5b3e69" + "e7958cb87392c2c2b60b1d1230b20e04"
			+ "90bd77f3483bb9b9b1c6f22b5e6f48c2" + "b4ecd5f01a4aa8281e38aeb6360b1af3"
			+ "e2280b6c20dd523225c6da63c38de1b0" + "8d590723948a535f579c487e5a38ad0e"
			+ "b0af48ec79ace8372d835a9df0c6d851" + "dcdb1b2798182244f8e431456cf88e65"
			+ "8a08f0f8bf0f156b1b8e9ecb641b58ff" + "ac8b2d36eed2dac5e272467e3d222f3f"
			+ "d7adf884aa8791775b0ed81dcc6abb0f" + "86ccbb52ea94baea98e947129fc2b4e9"
			+ "a87fea27a539e9a53f2398d747b36224" + "d29fe4b18e88640e8eec7f0d19a03aad"
			+ "83a3eeeef9153e891953cf68300424ac" + "a48ceaaab75a8e2b5fa8c3423c052dd7"
			+ "cdb02555653131b63792f412cb06794d" + "808e17555f3ebf11e2bbd88bbee40bd0"
			+ "a0b19d2ab70e6ed65b6aceaeae9d0ec4" + "c8de047564d20a8bf245825a5a445275"
			+ "fb158592be068d2eeed6e2f0f0d56712" + "9ced737bb6c4183d55464dd69685606b"
			+ "c428d05aa4751e4caa97e14c3c26b886" + "f53304714d9265dfd53dd99f4b3066a8"
			+ "993fe2c6d07b7fabe546a8038efe4029" + "bf8fdb78849a5f96de98520472bdd033"
			+ "ef73d256a5c0f77c963e66858f6d4440" + "95a8637627989aaddde7001379a44aa8"
			+ "bb127c53b17ec1595560c018580d5d52" + "e9d71b689dde71afaab8f01e6e10b4a6"
			+ "9226712162ab070dcab3961304ca70e8" + "b6b00d69bb55c8d13d607b97c5fd0d22"
			+ "e45c10c42a2b3b058cb89a7db77c506a" + "8eb98a7a9a5b04e377f3608e92adb242"
			+ "b267ed1940f1c61c55f038b237591ed3" + "df01e85f912e37a36b6c46dec52f6688"
			+ "8b61313bbabce2c62323ac4b3b3da015" + "ae397d8aa96c1b77abec975e0a0d081a"
			+ "d9c7dced53c7225596e7bd358c904a21" + "881cea14545c75757e50d64177da2e54"
			+ "aa242499697392d2dde50bd1d5d0b9e9" + "d4ad2dbfc3d07787955e4ec64b44e864"
			+ "84ec3c97da624ab4bd5af13bef0b113e" + "a6274bbdd0fadd61ecb1ad8aeacdd58e"
			+ "cfb11ead453994ba67de18eda5814af2" + "81ceb32c4b43fcf480eacf948770ced7"
			+ "a2425ff75e14fc31a1258379a94d028d" + "cad2f7f5359a3b3e096ee45813a04330"
			+ "fd87b5f28300ca0d8bca9d6e188853fc" + "9e74d1b791e07e48775ea264cf55347e"
			+ "c612062576589dda95364afe032a819e" + "f79687aed3eec5513a83ddbd83f52205"
			+ "9abe14cd44753b52c4926a9672793543" + "c16d9a0095928a2775b7053c0f178294"
			+ "f1c90080baf72cb15324c68b12dd6339" + "971da05074da7beed3f6fc16ebca5e04"
			+ "bce5086492111aea88f4bb1ca6bcf585" + "ec1e4a7db69561a52b31e9e3d06c32e6"
			+ "9392ee8e921d5d073aff322e62439fd0" + "b877aa3236a4b44909befeb9fad487c3"
			+ "e69594bec44de15b4c2ebe687989a9b4" + "901d7cf73ab0acd90f9d37014bf60a11"
			+ "b424dc35095cd80f538484c19ef38c95" + "e12e13424bb40e132865a5f206b06fba"
			+ "8cbccc096f5088cbf93f87b7442e45d4" + "afebff0bcb24aafef78f69a51539d749"
			+ "dbe6fecebdedd5beb573440e5a884d1c" + "89705f4136b4a59731680a88f8953031"
			+ "abcc77118461cefcfdc20d2b36ba7c3e" + "d6bf94d5e57a42bc3d32907604691b4d"
			+ "8637bd05af6c69b5a63f9a49c2c1b110" + "a7c5ac471b4784230fcf80dc33721d54"
			+ "d1b71758e219652bd3c36113404ea4a9" + "83126e978d4fdf3b645a1cac083126ea"
			+ "a3d70a3d70a3d70a3d70a3d70a3d70a4" + "cccccccccccccccccccccccccccccccd"
			+ "80000000000000000000000000000000" + "a0000000000000000000000000000000"
			+ "c8000000000000000000000000000000" + "fa000000000000000000000000000000"
			+ "9c400000000000000000000000000000" + "c3500000000000000000000000000000"
			+ "f4240000000000000000000000000000" + "98968000000000000000000000000000"
			+ "bebc2000000000000000000000000000" + "ee6b2800000000000000000000000000"
			+ "9502f900000000000000000000000000" + "ba43b740000000000000000000000000"
			+ "e8d4a510000000000000000000000000" + "9184e72a000000000000000000000000"
			+ "b5e620f4800000000000000000000000" + "e35fa931a00000000000000000000000"
			+ "8e1bc9bf040000000000000000000000" + "b1a2bc2ec50000000000000000000000"
			+ "de0b6b3a764000000000000000000000" + "8ac7230489e800000000000000000000"
			+ "ad78ebc5ac6200000000000000000000" + "d8d726b7177a80000000000000000000"
			+ "878678326eac90000000000000000000" + "a968163f0a57b4000000000000000000"
			+ "d3c21bcecceda1000000000000000000" + "84595161401484a00000000000000000"
			+ "a56fa5b99019a5c80000000000000000" + "cecb8f27f4200f3a0000000000000000"
			+ "813f3978f89409844000000000000000" + "a18f07d736b90be55000000000000000"
			+ "c9f2c9cd04674edea400000000000000" + "fc6f7c40458122964d00000000000000"
			+ "9dc5ada82b70b59df020000000000000" + "c5371912364ce3056c28000000000000"
			+ "f684df56c3e01bc6c732000000000000" + "9a130b963a6c115c3c7f400000000000"
			+ "c097ce7bc90715b34b9f100000000000" + "f0bdc21abb48db201e86d40000000000"
			+ "96769950b50d88f41314448000000000" + "bc143fa4e250eb3117d955a000000000"
			+ "eb194f8e1ae525fd5dcfab0800000000" + "92efd1b8d0cf37be5aa1cae500000000"
			+ "b7abc627050305adf14a3d9e40000000" + "e596b7b0c643c7196d9ccd05d0000000"
			+ "8f7e32ce7bea5c6fe4820023a2000000" + "b35dbf821ae4f38bdda2802c8a800000"
			+ "e0352f62a19e306ed50b2037ad200000" + "8c213d9da502de454526f422cc340000"
			+ "af298d050e4395d69670b12b7f410000" + "daf3f04651d47b4c3c0cdd765f114000"
			+ "88d8762bf324cd0fa5880a69fb6ac800" + "ab0e93b6efee00538eea0d047a457a00"
			+ "d5d238a4abe9806872a4904598d6d880" + "85a36366eb71f04147a6da2b7f864750"
			+ "a70c3c40a64e6c51999090b65f67d924" + "d0cf4b50cfe20765fff4b4e3f741cf6d"
			+ "82818f1281ed449fbff8f10e7a8921a4" + "a321f2d7226895c7aff72d52192b6a0d"
			+ "cbea6f8ceb02bb399bf4f8a69f764490" + "fee50b7025c36a0802f236d04753d5b4"
			+ "9f4f2726179a224501d762422c946590" + "c722f0ef9d80aad6424d3ad2b7b97ef5"
			+ "f8ebad2b84e0d58bd2e0898765a7deb2" + "9b934c3b330c857763cc55f49f88eb2f"
			+ "c2781f49ffcfa6d53cbf6b71c76b25fb" + "f316271c7fc3908a8bef464e3945ef7a"
			+ "97edd871cfda3a5697758bf0e3cbb5ac" + "bde94e8e43d0c8ec3d52eeed1cbea317"
			+ "ed63a231d4c4fb274ca7aaa863ee4bdd" + "945e455f24fb1cf88fe8caa93e74ef6a"
			+ "b975d6b6ee39e436b3e2fd538e122b44" + "e7d34c64a9c85d4460dbbca87196b616"
			+ "90e40fbeea1d3a4abc8955e946fe31cd" + "b51d13aea4a488dd6babab6398bdbe41"
			+ "e264589a4dcdab14c696963c7eed2dd1" + "8d7eb76070a08aecfc1e1de5cf543ca2"
			+ "b0de65388cc8ada83b25a55f43294bcb" + "dd15fe86affad91249ef0eb713f39ebe"
			+ "8a2dbf142dfcc7ab6e3569326c784337" + "acb92ed9397bf99649c2c37f07965404"
			+ "d7e77a8f87daf7fbdc33745ec97be906" + "86f0ac99b4e8dafd69a028bb3ded71a3"
			+ "a8acd7c0222311bcc40832ea0d68ce0c" + "d2d80db02aabd62bf50a3fa490c30190"
			+ "83c7088e1aab65db792667c6da79e0fa" + "a4b8cab1a1563f52577001b891185938"
			+ "cde6fd5e09abcf26ed4c0226b55e6f86" + "80b05e5ac60b6178544f8158315b05b4"
			+ "a0dc75f1778e39d6696361ae3db1c721" + "c913936dd571c84c03bc3a19cd1e38e9"
			+ "fb5878494ace3a5f04ab48a04065c723" + "9d174b2dcec0e47b62eb0d64283f9c76"
			+ "c45d1df942711d9a3ba5d0bd324f8394" + "f5746577930d6500ca8f44ec7ee36479"
			+ "9968bf6abbe85f207e998b13cf4e1ecb" + "bfc2ef456ae276e89e3fedd8c321a67e"
			+ "efb3ab16c59b14a2c5cfe94ef3ea101e" + "95d04aee3b80ece5bba1f1d158724a12"
			+ "bb445da9ca61281f2a8a6e45ae8edc97" + "ea1575143cf97226f52d09d71a3293bd"
			+ "924d692ca61be758593c2626705f9c56" + "b6e0c377cfa2e12e6f8b2fb00c77836c"
			+ "e498f455c38b997a0b6dfb9c0f956447" + "8edf98b59a373fec4724bd4189bd5eac"
			+ "b2977ee300c50fe758edec91ec2cb657" + "df3d5e9bc0f653e12f2967b66737e3ed"
			+ "8b865b215899f46cbd79e0d20082ee74" + "ae67f1e9aec07187ecd8590680a3aa11"
			+ "da01ee641a708de9e80e6f4820cc9495" + "884134fe908658b23109058d147fdcdd"
			+ "aa51823e34a7eedebd4b46f0599fd415" + "d4e5e2cdc1d1ea966c9e18ac7007c91a"
			+ "850fadc09923329e03e2cf6bc604ddb0" + "a6539930bf6bff4584db8346b786151c"
			+ "cfe87f7cef46ff16e612641865679a63" + "81f14fae158c5f6e4fcb7e8f3f60c07e"
			+ "a26da3999aef7749e3be5e330f38f09d" + "cb090c8001ab551c5cadf5bfd3072cc5"
			+ "fdcb4fa002162a6373d9732fc7c8f7f6" + "9e9f11c4014dda7e2867e7fddcdd9afa"
			+ "c646d63501a1511db281e1fd541501b8" + "f7d88bc24209a5651f225a7ca91a4226"
			+ "9ae757596946075f3375788de9b06958" + "c1a12d2fc39789370052d6b1641c83ae"
			+ "f209787bb47d6b84c0678c5dbd23a49a" + "9745eb4d50ce6332f840b7ba963646e0"
			+ "bd176620a501fbffb650e5a93bc3d898" + "ec5d3fa8ce427affa3e51f138ab4cebe"
			+ "93ba47c980e98cdfc66f336c36b10137" + "b8a8d9bbe123f017b80b0047445d4184"
			+ "e6d3102ad96cec1da60dc059157491e5" + "9043ea1ac7e4139287c89837ad68db2f"
			+ "b454e4a179dd187729babe4598c311fb" + "e16a1dc9d8545e94f4296dd6fef3d67a"
			+ "8ce2529e2734bb1d1899e4a65f58660c" + "b01ae745b101e9e45ec05dcff72e7f8f"
			+ "dc21a1171d42645d76707543f4fa1f73" + "899504ae72497eba6a06494a791c53a8"
			+ "abfa45da0edbde690487db9d17636892" + "d6f8d7509292d60345a9d2845d3c42b6"
			+ "865b86925b9bc5c20b8a2392ba45a9b2" + "a7f26836f282b7328e6cac7768d7141e"
			+ "d1ef0244af2364ff3207d795430cd926" + "8335616aed761f1f7f44e6bd49e807b8"
			+ "a402b9c5a8d3a6e75f16206c9c6209a6" + "cd036837130890a136dba887c37a8c0f"
			+ "802221226be55a64c2494954da2c9789" + "a02aa96b06deb0fdf2db9baa10b7bd6c"
			+ "c83553c5c8965d3d6f92829494e5acc7" + "fa42a8b73abbf48ccb772339ba1f17f9"
			+ "9c69a97284b578d7ff2a760414536efb" + "c38413cf25e2d70dfef5138519684aba"
			+ "f46518c2ef5b8cd17eb258665fc25d69" + "98bf2f79d5993802ef2f773ffbd97a61"
			+ "beeefb584aff8603aafb550ffacfd8fa" + "eeaaba2e5dbf678495ba2a53f983cf38"
			+ "952ab45cfa97a0b2dd945a747bf26183" + "ba756174393d88df94f971119aeef9e4"
			+ "e912b9d1478ceb177a37cd5601aab85d" + "91abb422ccb812eeac62e055c10ab33a"
			+ "b616a12b7fe617aa577b986b314d6009" + "e39c49765fdf9d94ed5a7e85fda0b80b"
			+ "8e41ade9fbebc27d14588f13be847307" + "b1d219647ae6b31c596eb2d8ae258fc8"
			+ "de469fbd99a05fe36fca5f8ed9aef3bb" + "8aec23d680043bee25de7bb9480d5854"
			+ "ada72ccc20054ae9af561aa79a10ae6a" + "d910f7ff28069da41b2ba1518094da04"
			+ "87aa9aff7904228690fb44d2f05d0842" + "a99541bf57452b28353a1607ac744a53"
			+ "d3fa922f2d1675f242889b8997915ce8" + "847c9b5d7c2e09b769956135febada11"
			+ "a59bc234db398c2543fab9837e699095" + "cf02b2c21207ef2e94f967e45e03f4bb"
			+ "8161afb94b44f57d1d1be0eebac278f5" + "a1ba1ba79e1632dc6462d92a69731732"
			+ "ca28a291859bbf937d7b8f7503cfdcfe" + "fcb2cb35e702af785cda735244c3d43e"
			+ "9defbf01b061adab3a0888136afa64a7" + "c56baec21c7a1916088aaa1845b8fdd0"
			+ "f6c69a72a3989f5b8aad549e57273d45" + "9a3c2087a63f639936ac54e2f678864b"
			+ "c0cb28a98fcf3c7f84576a1bb416a7dd" + "f0fdf2d3f3c30b9f656d44a2a11c51d5"
			+ "969eb7c47859e7439f644ae5a4b1b325" + "bc4665b596706114873d5d9f0dde1fee"
			+ "eb57ff22fc0c7959a90cb506d155a7ea" + "9316ff75dd87cbd809a7f12442d588f2"
			+ "b7dcbf5354e9bece0c11ed6d538aeb2f" + "e5d3ef282a242e818f1668c8a86da5fa"
			+ "8fa475791a569d10f96e017d694487bc" + "b38d92d760ec445537c981dcc395a9ac"
			+ "e070f78d3927556a85bbe253f47b1417" + "8c469ab843b8956293956d7478ccec8e"
			+ "af58416654a6babb387ac8d1970027b2" + "db2e51bfe9d0696a06997b05fcc0319e"
			+ "88fcf317f22241e2441fece3bdf81f03" + "ab3c2fddeeaad25ad527e81cad7626c3"
			+ "d60b3bd56a5586f18a71e223d8d3b074" + "85c7056562757456f6872d5667844e49"
			+ "a738c6bebb12d16cb428f8ac016561db" + "d106f86e69d785c7e13336d701beba52"
			+ "82a45b450226b39cecc0024661173473" + "a34d721642b0608427f002d7f95d0190"
			+ "cc20ce9bd35c78a531ec038df7b441f4" + "ff290242c83396ce7e67047175a15271"
			+ "9f79a169bd203e410f0062c6e984d386" + "c75809c42c684dd152c07b78a3e60868"
			+ "f92e0c3537826145a7709a56ccdf8a82" + "9bbcc7a142b17ccb88a66076400bb691"
			+ "c2abf989935ddbfe6acff893d00ea435" + "f356f7ebf83552fe0583f6b8c4124d43"
			+ "98165af37b2153dec3727a337a8b704a" + "be1bf1b059e9a8d6744f18c0592e4c5c"
			+ "eda2ee1c7064130c1162def06f79df73" + "9485d4d1c63e8be78addcb5645ac2ba8"
			+ "b9a74a0637ce2ee16d953e2bd7173692" + "e8111c87c5c1ba99c8fa8db6ccdd0437"
			+ "910ab1d4db9914a01d9c9892400a22a2" + "b54d5e4a127f59c82503beb6d00cab4b"
			+ "e2a0b5dc971f303a2e44ae64840fd61d" + "8da471a9de737e245ceaecfed289e5d2"
			+ "b10d8e1456105dad7425a83e872c5f47" + "dd50f1996b947518d12f124e28f77719"
			+ "8a5296ffe33cc92f82bd6b70d99aaa6f" + "ace73cbfdc0bfb7b636cc64d1001550b"
			+ "d8210befd30efa5a3c47f7e05401aa4e" + "8714a775e3e95c7865acfaec34810a71"
			+ "a8d9d1535ce3b3967f1839a741a14d0d" + "d31045a8341ca07c1ede48111209a050"
			+ "83ea2b892091e44d934aed0aab460432" + "a4e4b66b68b65d60f81da84d5617853f"
			+ "ce1de40642e3f4b936251260ab9d668e" + "80d2ae83e9ce78f3c1d72b7c6b426019"
			+ "a1075a24e4421730b24cf65b8612f81f" + "c94930ae1d529cfcdee033f26797b627"
			+ "fb9b7cd9a4a7443c169840ef017da3b1" + "9d412e0806e88aa58e1f289560ee864e"
			+ "c491798a08a2ad4ef1a6f2bab92a27e2" + "f5b5d7ec8acb58a2ae10af696774b1db"
			+ "9991a6f3d6bf1765acca6da1e0a8ef29" + "bff610b0cc6edd3f17fd090a58d32af3"
			+ "eff394dcff8a948eddfc4b4cef07f5b0" + "95f83d0a1fb69cd94abdaf101564f98e"
			+ "bb764c4ca7a4440f9d6d1ad41abe37f1" + "ea53df5fd18d551384c86189216dc5ed"
			+ "92746b9be2f8552c32fd3cf5b4e49bb4" + "b7118682dbb66a773fbc8c33221dc2a1"
			+ "e4d5e82392a405150fabaf3feaa5334a" + "8f05b1163ba6832d29cb4d87f2a7400e"
			+ "b2c71d5bca9023f8743e20e9ef511012" + "df78e4b2bd342cf6914da9246b255416"
			+ "8bab8eefb6409c1a1ad089b6c2f7548e" + "ae9672aba3d0c320a184ac2473b529b1"
			+ "da3c0f568cc4f3e8c9e5d72d90a2741e" + "8865899617fb18717e2fa67c7a658892"
			+ "aa7eebfb9df9de8dddbb901b98feeab7" + "d51ea6fa85785631552a74227f3ea565"
			+ "8533285c936b35ded53a88958f87275f" + "a67ff273b84603568a892abaf368f137"
			+ "d01fef10a657842c2d2b7569b0432d85" + "8213f56a67f6b29b9c3b29620e29fc73"
			+ "a298f2c501f45f428349f3ba91b47b8f" + "cb3f2f7642717713241c70a936219a73"
			+ "fe0efb53d30dd4d7ed238cd383aa0110" + "9ec95d1463e8a506f4363804324a40aa"
			+ "c67bb4597ce2ce48b143c6053edcd0d5" + "f81aa16fdc1b81dadd94b7868e94050a"
			+ "9b10a4e5e9913128ca7cf2b4191c8326" + "c1d4ce1f63f57d72fd1c2f611f63a3f0"
			+ "f24a01a73cf2dccfbc633b39673c8cec" + "976e41088617ca01d5be0503e085d813"
			+ "bd49d14aa79dbc824b2d8644d8a74e18" + "ec9c459d51852ba2ddf8e7d60ed1219e"
			+ "93e1ab8252f33b45cabb90e5c942b503" + "b8da1662e7b00a173d6a751f3b936243"
			+ "e7109bfba19c0c9d0cc512670a783ad4" + "906a617d450187e227fb2b80668b24c5"
			+ "b484f9dc9641e9dab1f9f660802dedf6" + "e1a63853bbd264515e7873f8a0396973"
			+ "8d07e33455637eb2db0b487b6423e1e8" + "b049dc016abc5e5f91ce1a9a3d2cda62"
			+ "dc5c5301c56b75f77641a140cc7810fb" + "89b9b3e11b6329baa9e904c87fcb0a9d"
			+ "ac2820d9623bf429546345fa9fbdcd44" + "d732290fbacaf133a97c177947ad4095"
			+ "867f59a9d4bed6c049ed8eabcccc485d" + "a81f301449ee8c705c68f256bfff5a74"
			+ "d226fc195c6a2f8c73832eec6fff3111" + "83585d8fd9c25db7c831fd53c5ff7eab"
			+ "a42e74f3d032f525ba3e7ca8b77f5e55" + "cd3a1230c43fb26f28ce1bd2e55f35eb"
			+ "80444b5e7aa7cf857980d163cf5b81b3" + "a0555e361951c366d7e105bcc332621f"
			+ "c86ab5c39fa634408dd9472bf3fefaa7" + "fa856334878fc150b14f98f6f0feb951"
			+ "9c935e00d4b9d8d26ed1bf9a569f33d3" + "c3b8358109e84f070a862f80ec4700c8"
			+ "f4a642e14c6262c8cd27bb612758c0fa" + "98e7e9cccfbd7dbd8038d51cb897789c"
			+ "bf21e44003acdd2ce0470a63e6bd56c3" + "eeea5d50049814781858ccfce06cac74"
			+ "95527a5202df0ccb0f37801e0c43ebc8" + "baa718e68396cffdd30560258f54e6ba"
			+ "e950df20247c83fd47c6b82ef32a2069" + "91d28b7416cdd27e4cdc331d57fa5441"
			+ "b6472e511c81471de0133fe4adf8e952" + "e3d8f9e563a198e558180fddd97723a6"
			+ "8e679c2f5e44ff8f570f09eaa7ea7648";

	/** A copy of the powers-of-five table, for the test that re-derives it. */
	public static long[] powersOfFive() {
		return Powers.FIVE.clone();
	}

	/**
	 * The double nearest to {@code w * 10^q}, {@code w} read as an unsigned 64-bit
	 * number with at most 19 decimal digits (so below 2^64) and non-zero. Overflow
	 * gives {@link Double#POSITIVE_INFINITY}, underflow {@code 0.0}.
	 */
	public static double toDouble(long w, int q) {
		return Double.longBitsToDouble(toBits(w, q));
	}

	public static long toBits(long w, int q) {
		if (q < SMALLEST_POWER_OF_TEN) {
			return 0L;
		}
		if (q > LARGEST_POWER_OF_TEN) {
			return INFINITY_BITS;
		}
		final long[] five = Powers.FIVE;

		// normalise: most significant bit set
		int lz = Long.numberOfLeadingZeros(w);
		w <<= lz;

		// product approximation: w * 5^q as a 128-bit number (its high word is what
		// matters)
		int index = 2 * (q - SMALLEST_POWER_OF_TEN);
		long high = Math.unsignedMultiplyHigh(w, five[index]);
		long low = w * five[index];
		// 52 + 3 bits are needed; if the ones just below them are all 1s the rounding
		// could hinge on the lower half of the power, so refine with it
		if ((high & 0x1FFL) == 0x1FFL) {
			long secondHigh = Math.unsignedMultiplyHigh(w, five[index + 1]);
			low += secondHigh;
			if (Long.compareUnsigned(secondHigh, low) > 0) {
				high++;
			}
		}
		int upperBit = (int) (high >>> 63);
		int shift = upperBit + 64 - 52 - 3;
		long mantissa = high >>> shift;

		// floor(log2(10^q)) + 63, with the exponent bias
		int power2 = (((152170 + 65536) * q) >> 16) + 63 + upperBit - lz + 1023;

		if (power2 <= 0) { // subnormal, or zero
			if (-power2 + 1 >= 64) {
				return 0L;
			}
			mantissa >>>= -power2 + 1;
			mantissa += mantissa & 1; // round up
			mantissa >>>= 1;
			// rounding may just have made it the smallest normal number
			power2 = mantissa < (1L << 52) ? 0 : 1;
			return ((long) power2 << 52) | mantissa;
		}

		// halfway between two doubles: round to even instead of up. Only possible where
		// 5^q fits one word, i.e. for small |q|.
		if (Long.compareUnsigned(low, 1) <= 0 && q >= -4 && q <= 23 && (mantissa & 3) == 1
				&& (mantissa << shift) == high) {
			mantissa &= ~1L;
		}

		mantissa += mantissa & 1; // round up
		mantissa >>>= 1;
		if (mantissa >= (2L << 52)) {
			mantissa = 1L << 52;
			power2++;
		}
		mantissa &= ~(1L << 52); // drop the implicit bit
		if (power2 >= 0x7FF) {
			return INFINITY_BITS;
		}
		return ((long) power2 << 52) | mantissa;
	}
}
