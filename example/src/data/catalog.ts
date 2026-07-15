import { Image } from 'react-native';
import type { ImageSourcePropType } from 'react-native';
import { CATEGORY_ACCENT, type Category } from './categories';

// Local, bundled catalog. Two asset sets are merged: the original curated
// `assets/images` collection (tagged by category) and the newer `assets/stills`
// set (people/editorial stock, dimensions + alt text from the scraped
// metadata.json). Served through the same `useCatalog` / `useCatalogItem` hook
// API the screens already consume, so no remote fetch is involved.

export interface CatalogItem {
  id: string;
  source: ImageSourcePropType;
  category: Category;
  title: string;
  accent: string;
  color: string;
  author: string;
  description: string | null;
  alt_description: string | null;
  ratio: number;
  width: number;
  height: number;
  blur_hash: string | null;
}

// ── Original curated set (assets/images) ──────────────────────────────────────

const IMAGE_SOURCES: Array<{
  source: ImageSourcePropType;
  category: Category;
}> = [
  {
    source: require('../../assets/images/9ba092c8-2d9e-4614-8fb7-4774e45ab457.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/9bdee9a0-46f5-4ce2-afac-e2a63fe90b64.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/9c4345e2-63f4-4faa-b9ce-b1ee85d0c9d9.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/9c7b321d-75c3-48b3-b32b-21a9e598cb94.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/9c859e73-90cb-41b0-b4d3-5d7077fa788f.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/9c866008-e7ac-4016-a216-a3125bbe9bf6.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/9c949bae-ce97-4b67-a90a-34a384e03e63.jpg'),
    category: 'Animals',
  },
  {
    source: require('../../assets/images/9cb34db0-13f3-4bd8-af5d-230abcfe8196.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/9cf24df8-e1b6-4eb2-833a-e3a9397408f7.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/9d452719-38ea-4fe8-b188-9e7c47fbf5e4.jpg'),
    category: 'Animals',
  },
  {
    source: require('../../assets/images/9d465d07-f876-4c6a-a946-9e621fc6c363.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/9d8a4f0e-81f3-425f-ab85-8efc7e3cb5b6.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/9d8a4f92-9cd1-47d0-8f7c-9090c35a99e9.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/9d8a4fe4-aa56-4750-8bf3-d0dbc5a81a09.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/9d8a5043-09cd-408b-81b9-a62b15a1888c.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/9db1f69e-3e1e-4d18-b5dd-fb83b98fbd79.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/9dbdd505-96fe-4f90-87cd-69959ff09660.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/9e03f2d4-58fa-489d-96a7-25789b14b4c6.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/9e0c6501-1e8f-4428-8987-569ef1389449.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/9e10098e-866b-4c3d-8ca9-f5e8c0243f8f.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/9e1a38b2-b2c9-4744-afa8-9920acd07909.jpg'),
    category: 'Animals',
  },
  {
    source: require('../../assets/images/9e1a63ae-9ec2-41bf-936b-84b076cbfe89.jpg'),
    category: 'Underwater',
  },
  {
    source: require('../../assets/images/9e242635-a789-410b-8a3c-4ea625b7beeb.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/9e3c7299-6b8f-47e6-a549-83caa9572864.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/9e3e5add-485c-467d-827b-55cf402481d2.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/9e45edcf-0c74-4fa9-a180-5a5a7345eca8.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/9e59c49f-7700-4e5b-be14-32c070d5af43.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/9e7f4dc8-999f-4f22-87ed-201f8151cd64.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/9ef3281a-a251-4733-b2b4-166429b9737b.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/9f1d8d9a-39bd-4dd6-a65f-7b589c0a5bbd.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/9f256029-83b7-4cca-ab40-34aa82623fee.jpg'),
    category: 'Sports',
  },
  {
    source: require('../../assets/images/9f4b5744-7ad6-42ae-a56a-c0a05830639c.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/9f6186cb-dda2-4df6-a261-3333cccf0276.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/9f61933b-541d-481c-8777-4c814188d6ff.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/9f65c275-5678-405b-828b-a8d6d3f836d1.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/9f6820da-c77a-49ce-9dd8-faf244f2b4d4.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/a01ec15d-8a16-40be-8c7b-b516b260c284.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/a02b026a-c630-4d4e-aae0-606e348c5de5.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/a0395656-c22e-4e94-9a49-480bbf5ca458.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/a03ad659-7fb7-454a-8636-c30e9f666810.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/a0738cb5-234c-45ee-b006-bcdc4c77919c.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/a07e2d49-8e4b-4818-a83e-400c0218c788.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/a07fb1ae-b523-4a39-972e-e689c061b1af.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/a0919f92-a2bf-4250-8ea2-e2637ac425d1.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/a0a9a58b-d856-415b-83a5-e6fa8394257c.jpg'),
    category: 'Animals',
  },
  {
    source: require('../../assets/images/a0b1fa0d-1751-4a8f-8783-c8f82e697f71.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/a0bad78d-330b-42cb-943b-6c640766042f.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/a0bae6a4-d4a3-45e9-91da-b25b4e6a0068.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/a0c33dc8-7ea0-4c2f-afb3-52da8a7c0956.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/a0c52cce-1a34-4108-9d8e-54e6ed148ef2.jpg'),
    category: 'Underwater',
  },
  {
    source: require('../../assets/images/a0ca83d6-d331-4b68-9f96-985ac8912b64.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/a0d5d70b-21be-4bac-86de-c1fd44a04ae1.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/a0f0e023-1596-4ecc-b771-b6717c96f67d.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/a0f1dd90-a6f4-4517-8b41-ad82bb2e96a8.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/a0f44c2b-de69-448d-9354-fad6324d4157.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/a0fe5092-ca25-464b-8ff6-dc0b55754a4d.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/a10406f4-b81b-4456-a6bb-313a8849ba00.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/a110becf-fc2a-455b-b63c-8f97c8605331.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/a112444e-aa0a-4def-a3ec-25e232a4af59.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/a1124490-93df-41d7-86a8-b08bab3fbd60.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/a1135b99-8f51-49a0-aa8d-a91f5a2e9d71.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/a1153d6f-02b7-4db8-b45e-0c315774161e.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/a1153da7-2686-4a2c-b575-2dbaec5c0fba.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/a138c014-260b-4154-a90d-46f62def4fc5.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/a1448704-8edc-4c67-a8aa-070551bc0686.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/a188612c-9bdb-46ef-be68-d53e00fa8bd2.jpg'),
    category: 'Animals',
  },
  {
    source: require('../../assets/images/a18f2c20-d4c5-460b-823f-84a9dd5c7f04.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/a1966656-2931-4e12-a72c-d40d432ab139.jpg'),
    category: 'Landscape',
  },
  {
    source: require('../../assets/images/a1b33a05-084c-4b3d-b7d8-ae9730b81d0c.jpg'),
    category: 'Abstract',
  },
  {
    source: require('../../assets/images/a1b33a0e-5350-4889-8019-89899658e505.jpg'),
    category: 'Nature',
  },
  {
    source: require('../../assets/images/a1b944cf-6ed8-4def-a0e2-b8e899d07c13.jpg'),
    category: 'Architecture',
  },
  {
    source: require('../../assets/images/p-9831466d-8d44-4716-a5e1-328e9366185e.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-9877b4d9-adbb-4bff-bc0b-4802f797d4ab.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-98946414-c532-4896-9311-a8eba52fa06d.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-98dc3a7a-5b07-4084-9156-101443846ce1.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-98f6662b-2d15-4e39-a953-e92f5ece2688.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-98f88e35-b619-4a0d-9d0c-d80d3a01b9cc.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-98fb1bc2-5c32-41c0-a1a0-bdc93f70846e.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-9922d15e-8f79-4dec-b2ad-c9fa1c84e531.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-9926659d-a433-465c-949a-2bdf83c79e52.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-9926bf31-ea3f-4b49-940b-0236446051d0.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-9926fd1b-76ff-4755-9664-7684569f688e.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-992e881a-3f8e-47de-b612-8d03f86b064b.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-995f1093-6418-4b75-8c7a-4b4e1fdd238d.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-995f1459-096f-46a9-a8ee-5ec5200a0ad0.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-996a4c1c-9dde-4f9e-836e-8c4f7bace057.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-99749745-9baa-4e9c-acd5-0d2cad2d2aa1.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-9974c95b-d704-45c4-b511-26b908446333.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-9974cfc4-6bbb-419a-8931-c11e169dce6c.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-99bb8611-7a4c-44fb-85bc-06ac11f7ef15.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-99bfd0cc-203e-4ffd-9ee1-6d13e0cbe29e.jpg'),
    category: 'Portrait',
  },
  {
    source: require('../../assets/images/p-99c2f6d5-9938-4b6c-bd28-20205c9b605a.jpg'),
    category: 'Portrait',
  },
];

// ── Stills set (assets/stills) — dimensions + alt from metadata.json ─────────

const STILLS_SOURCES: Array<{
  source: ImageSourcePropType;
  width: number;
  height: number;
  alt: string;
}> = [
  {
    source: require('../../assets/stills/9bc48004-4260-49a2-94a6-a1856dd1fdf9.jpg'),
    width: 211,
    height: 281,
    alt: 'Model woman sitting wearing a long-sleeved black dress and makeup.',
  },
  {
    source: require('../../assets/stills/9c0480ac-0c32-4542-848c-066a1c3f5b01.jpg'),
    width: 187,
    height: 281,
    alt: "In a dark room, only the woman's face is visible.",
  },
  {
    source: require('../../assets/stills/9c295fca-caf7-4a67-af5d-288a115c8057.jpg'),
    width: 148,
    height: 281,
    alt: 'Two women wearing traditional burqas with intricate embroidery, embracing each other in a hug.',
  },
  {
    source: require('../../assets/stills/9c761829-3f55-41c5-9ccd-40796a161b55.jpg'),
    width: 187,
    height: 281,
    alt: 'Woman with both hands on her face and wearing a dress.',
  },
  {
    source: require('../../assets/stills/9cf20dab-741f-4458-b27b-d6ce779c91ba.jpg'),
    width: 197,
    height: 281,
    alt: 'A person wearing a black, shiny mask illuminated by colorful lights.',
  },
  {
    source: require('../../assets/stills/9d14d787-d5ca-42d8-92d9-f8a12fc2f600.jpg'),
    width: 187,
    height: 281,
    alt: 'Blurry closeup of person wearing sunglasses in a dark area.',
  },
  {
    source: require('../../assets/stills/9d3f6403-7bd2-4195-bf22-b75a1fef947f.jpg'),
    width: 187,
    height: 281,
    alt: 'A close up of a woman with makeup on her face',
  },
  {
    source: require('../../assets/stills/9d4eb643-6d97-4ea6-a24e-43da468e0a32.jpg'),
    width: 187,
    height: 281,
    alt: "A close-up in black and white highlights the delicate curves and textures of a person's lips, the upper half of their face obscured by shadow and a subtle blur.",
  },
  {
    source: require('../../assets/stills/9d527af9-0fdd-4722-9d60-6c83d49ab470.jpg'),
    width: 281,
    height: 281,
    alt: 'A portrait of a red haired lady',
  },
  {
    source: require('../../assets/stills/9d5c87be-67a3-423d-93af-d4e439da9bd9.jpg'),
    width: 186,
    height: 281,
    alt: 'A woman stands in dim greenish lighting, her face partially obscured in shadow, with only subtle highlights illuminating the side of her hair and shoulder, creating a mysterious and introspective atmosphere.',
  },
  {
    source: require('../../assets/stills/9d8539ea-e04d-4886-9f99-baff430a17ef.jpg'),
    width: 225,
    height: 281,
    alt: 'A dark headed woman wearing jewelry',
  },
  {
    source: require('../../assets/stills/9d952562-a5f2-4254-8788-47691b0c6d0c.jpg'),
    width: 187,
    height: 281,
    alt: 'a woman blurred by the speed of the camera',
  },
  {
    source: require('../../assets/stills/9da61dde-15f8-4c3b-addb-68a504f40f5a.jpg'),
    width: 187,
    height: 281,
    alt: 'An abstract profile of an elderly person with blurred lights',
  },
  {
    source: require('../../assets/stills/9db8b508-4051-4ab9-a436-f88111e160d5.jpg'),
    width: 187,
    height: 281,
    alt: 'A person with their face covered by one arm, wearing a black blouse and long, shiny earrings.',
  },
  {
    source: require('../../assets/stills/9ddfae27-a8bf-46a9-a1ea-441a1834da16.jpg'),
    width: 187,
    height: 281,
    alt: "Abstract portrait and blur of a person's face.",
  },
  {
    source: require('../../assets/stills/9de4768b-49b6-4d9b-87c0-3826b04848f5.jpg'),
    width: 187,
    height: 281,
    alt: "Details of a woman's face, lips with dark glossy lipstick and shoulders are illuminated against a predominantly dark background.",
  },
  {
    source: require('../../assets/stills/9de98a9a-bfe2-4aa2-a71f-06a85c16e3ea.jpg'),
    width: 256,
    height: 281,
    alt: 'A person wearing a shiny blue high-neck top with bright red lipstick, set against a plain background.',
  },
  {
    source: require('../../assets/stills/9dfbae36-fad7-4cb5-a082-fef54c861d86.jpg'),
    width: 281,
    height: 281,
    alt: 'A dark haired woman in an old fashioned dress, with a ghostly appearance',
  },
  {
    source: require('../../assets/stills/9e502473-cd62-43eb-8dcd-7d18c6799f96.jpg'),
    width: 193,
    height: 281,
    alt: 'A woman stands in a dark room with only her face illuminated.',
  },
  {
    source: require('../../assets/stills/9e52e6be-f4f2-4eaf-b627-38b9ecb1fac1.jpg'),
    width: 187,
    height: 281,
    alt: 'A blurry image of a woman in dark water, body mostly submerged, her face lit and breaking the surface where water reflects on her skin from light shining through, a black bra top visible.',
  },
  {
    source: require('../../assets/stills/9e68b5dc-0be6-4cca-abcb-e23e41d7dcdf.jpg'),
    width: 281,
    height: 225,
    alt: 'A woman walking with her kids near a riverbank during the day.',
  },
  {
    source: require('../../assets/stills/9e808cdc-19a0-40eb-bd40-d757a9659051.jpg'),
    width: 206,
    height: 281,
    alt: 'A grainy, dark image of a woman with shoulder-length hair, partially backlit, creating a shadowy effect.',
  },
  {
    source: require('../../assets/stills/9fe6d583-6801-4695-9fff-017b8f64c4e6.jpg'),
    width: 187,
    height: 281,
    alt: "A person's eye creepily staring through a strip of light in darkness",
  },
  {
    source: require('../../assets/stills/9fe6d6d3-48a8-47af-a4a3-e4979bade91d.jpg'),
    width: 187,
    height: 281,
    alt: 'A multiple exposure portrait of a dark haired woman standing in front of a reddish orange backdrop.',
  },
  {
    source: require('../../assets/stills/a03ad659-7fb7-454a-8636-c30e9f666810.jpg'),
    width: 225,
    height: 281,
    alt: 'An abstract and grainy black and white close-up captures the distorted and blurry features of a human face.',
  },
  {
    source: require('../../assets/stills/a0567267-6c65-489a-a8aa-4659df57da4b.jpg'),
    width: 187,
    height: 281,
    alt: 'A thoughtful woman with long dark hair lies in a field of tall golden grass, her face partially lit by the setting sun.',
  },
  {
    source: require('../../assets/stills/a06358b9-f153-4f12-a018-84482b4884ee.jpg'),
    width: 211,
    height: 281,
    alt: 'A dramatic close-up portrait of a young woman with blue eyes, with a patterned strip of light falling across her face.',
  },
  {
    source: require('../../assets/stills/a07e2f36-ce82-434d-a7c2-1e4c200c3c47.jpg'),
    width: 194,
    height: 281,
    alt: "An abstract and blurry portrait captures a woman's face in motion with streaks of pink light against a blue background.",
  },
  {
    source: require('../../assets/stills/a1285be4-b4b1-46d2-a9c4-9b99b7451ae5.jpg'),
    width: 187,
    height: 281,
    alt: 'Two silhouetted children stand by a foggy riverbank next to a small garden of flowers.',
  },
  {
    source: require('../../assets/stills/a14123bd-eaa3-454a-84b4-fae801977b48.jpg'),
    width: 187,
    height: 281,
    alt: 'A striking black and white portrait of a woman with long dark hair, her face and body partially illuminated by a single beam of light.',
  },
  {
    source: require('../../assets/stills/a1c98b82-467c-4297-977e-2bc599b6b75a.jpg'),
    width: 189,
    height: 281,
    alt: 'A grainy, monochrome photograph captures a flock of birds perched atop a distant, shadowed cliff.',
  },
  {
    source: require('../../assets/stills/a1cae135-24cc-4970-b83d-29979ffb1ada.jpg'),
    width: 211,
    height: 281,
    alt: 'An intentionally blurred and grainy photograph captures the energy of a crowd at a concert under bright blue spotlights.',
  },
  {
    source: require('../../assets/stills/a2197e82-be1e-46a2-a055-29e864c9f096.jpg'),
    width: 225,
    height: 281,
    alt: 'Two women dressed in black pose for a conceptual portrait with their faces completely obscured by wrapped yellow rope.',
  },
  {
    source: require('../../assets/stills/p-97f1794b-ae06-4ee6-8904-cdb5ecde3f6d.jpg'),
    width: 187,
    height: 281,
    alt: 'A person with a neutral expression wearing jewelry and making a cool gesture with their finger',
  },
  {
    source: require('../../assets/stills/p-986bceca-f6a9-4eed-a556-e35b48b7f3fa.jpg'),
    width: 211,
    height: 281,
    alt: 'A woman posing in a dark room with her hands on her face and eyes closed',
  },
  {
    source: require('../../assets/stills/p-986c3012-e37e-4b57-9c18-536bf683f5ea.jpg'),
    width: 187,
    height: 281,
    alt: 'A woman dressed in black with her shadow reflected on the nearby wall',
  },
  {
    source: require('../../assets/stills/p-9884b71f-3096-4451-b3f2-7d457dd32a34.jpg'),
    width: 187,
    height: 281,
    alt: 'A woman standing next to a building holding black balloons',
  },
  {
    source: require('../../assets/stills/p-98e03357-dfc6-4b63-8085-bc9cf13d8005.jpg'),
    width: 187,
    height: 281,
    alt: 'Darkhaired woman in black attire partially illuminated by a light source in a dimly lit room',
  },
  {
    source: require('../../assets/stills/p-98f88514-4491-4eee-a3ce-987532846ce1.jpg'),
    width: 211,
    height: 281,
    alt: 'One person with neutral expression wearing clothing with focus on their face head eyelash and jaw',
  },
  {
    source: require('../../assets/stills/p-99120b75-7a81-4c5b-b1a9-4846b420a3ae.jpg'),
    width: 186,
    height: 281,
    alt: 'Excited woman with a smile and finger on her lips',
  },
  {
    source: require('../../assets/stills/p-992ee4d7-8ca8-40e9-a5d4-03253a1c5f52.jpg'),
    width: 187,
    height: 281,
    alt: 'A man holds his hands in front of his face',
  },
  {
    source: require('../../assets/stills/p-999de402-752c-4e37-a831-2543aac8226d.jpg'),
    width: 281,
    height: 188,
    alt: 'A large group of people gathered outdoors',
  },
  {
    source: require('../../assets/stills/p-99a56547-16ee-47ca-a16e-b784b68ecb08.jpg'),
    width: 211,
    height: 281,
    alt: 'A woman in a room with multiple exposures',
  },
  {
    source: require('../../assets/stills/p-99cc0237-8555-4c88-bdec-9faa4646161d.jpg'),
    width: 225,
    height: 281,
    alt: 'Bronze statue with raised hand reflects light.',
  },
  {
    source: require('../../assets/stills/p-9a2a3e13-6375-4a04-9d57-611defa302c1.jpg'),
    width: 281,
    height: 211,
    alt: "A blurry image of a woman's face outdoors",
  },
  {
    source: require('../../assets/stills/p-9a2c9046-a273-45b8-b7b4-78c726817293.jpg'),
    width: 187,
    height: 281,
    alt: 'Woman with winged eyeliner stares at camera',
  },
  {
    source: require('../../assets/stills/p-9a38418b-815a-4545-b18d-36e12f7084ec.jpg'),
    width: 187,
    height: 281,
    alt: 'A blonde in sheer black with a mysterious veil and vivid lips sits in a dimly lit room.',
  },
];

// ── Build catalog ─────────────────────────────────────────────────────────────

function ratioOf(source: ImageSourcePropType, fallback = 1): number {
  const src = Image.resolveAssetSource(source);
  return src?.width && src?.height ? src.width / src.height : fallback;
}

const IMAGE_ITEMS: CatalogItem[] = IMAGE_SOURCES.map((entry, i) => {
  const src = Image.resolveAssetSource(entry.source);
  const width = src?.width ?? 0;
  const height = src?.height ?? 0;
  const accent = CATEGORY_ACCENT[entry.category];
  return {
    id: `i${String(i + 1).padStart(3, '0')}`,
    source: entry.source,
    category: entry.category,
    title: entry.category,
    accent,
    color: accent,
    author: 'Collection',
    description: null,
    alt_description: null,
    ratio: width && height ? width / height : 1,
    width,
    height,
    blur_hash: null,
  };
});

export const STILLS_ITEMS: CatalogItem[] = STILLS_SOURCES.map((entry, i) => {
  const accent = CATEGORY_ACCENT.Portrait;
  return {
    id: `s${String(i + 1).padStart(3, '0')}`,
    source: entry.source,
    category: 'Portrait' as Category,
    title: entry.alt || 'Untitled',
    accent,
    color: accent,
    author: 'Stills',
    description: entry.alt || null,
    alt_description: entry.alt || null,
    ratio:
      entry.width && entry.height
        ? entry.width / entry.height
        : ratioOf(entry.source),
    width: entry.width,
    height: entry.height,
    blur_hash: null,
  };
});

export const CATALOG: CatalogItem[] = [...IMAGE_ITEMS];

const BY_ID = new Map<string, CatalogItem>(CATALOG.map((it) => [it.id, it]));

export const getCatalogItem = (id: string): CatalogItem | undefined =>
  BY_ID.get(id);

export const PORTRAIT_ITEMS = CATALOG.filter(
  (it) => it.category === 'Portrait'
);

// ── Hook API (kept for the SDK-57 screens; data is static/local) ──────────────

export function useCatalog() {
  return { catalog: CATALOG, isLoading: false, isError: false };
}

export function useCatalogItem(id: string) {
  return { item: getCatalogItem(id) ?? null, isLoading: false };
}

// ── Category filter list ──────────────────────────────────────────────────────

export const CATEGORIES = [
  { label: 'All', accent: '#aaaaaa' },
  { label: 'Portrait' as Category, accent: CATEGORY_ACCENT.Portrait },
  { label: 'Nature' as Category, accent: CATEGORY_ACCENT.Nature },
  { label: 'Landscape' as Category, accent: CATEGORY_ACCENT.Landscape },
  { label: 'Architecture' as Category, accent: CATEGORY_ACCENT.Architecture },
  { label: 'Abstract' as Category, accent: CATEGORY_ACCENT.Abstract },
  { label: 'Animals' as Category, accent: CATEGORY_ACCENT.Animals },
  { label: 'Underwater' as Category, accent: CATEGORY_ACCENT.Underwater },
  { label: 'Sports' as Category, accent: CATEGORY_ACCENT.Sports },
];
