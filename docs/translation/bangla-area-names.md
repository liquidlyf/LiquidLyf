# Bangla area names to review

**Reviewed (C8, 16 Nov 2026).** The maintainer checked all 67, with each name compared against the upazila's or
city corporation's own `gov.bd` portal: 58 are right, the 7 in the last column change, and 2 stay as they
are (Dupchachia and Osmaninagar, whose portals disagree with themselves). Indurkani was renamed back to
Zianagar (জিয়ানগর) in 2026, so its English name may need to change too. In the English column,
"Bhuanpur" may be a typo for "Bhuapur", and "Chapainababganj" is spelled differently from
"Chapainawabganj Sadar". These come from the BBS list; the app's maintainers decide.

The app's area list ([ADR 0015](../adr/0015-area-data.md)) takes its English names and codes from the
official BBS list. Most Bangla names come from a community list (MIT licence). We wrote these **67**
ourselves, for areas that list lacks or spells clearly wrong, so they need a check by a native speaker.
Please check each spelling as people in that place write it.

To suggest a fix, open an issue with the code and the right spelling, or write it in the last column
and send a pull request. The app's maintainers then update `app/scripts/bd_geo_bn_overrides.json` and
rebuild `bd-geo.json`.

| Division | Kind | English | Bangla now | Code | Right Bangla (if different) |
|---|---|---|---|---|---|
| Barishal | City corporation, Barishal | Barishal City Corporation | বরিশাল সিটি কর্পোরেশন | BD10065000 |  |
| Barishal | Upazila, Barishal | Barishal Sadar (Kotwali) | বরিশাল সদর (কোতোয়ালি) | BD10060051 | বরিশাল সদর (কোতোয়ালী) |
| Barishal | Upazila, Barishal | Hijla | হিজলা | BD10060036 |  |
| Barishal | Upazila, Barishal | Ujirpur | উজিরপুর | BD10060094 |  |
| Barishal | Upazila, Bhola | Borhanuddin | বোরহানউদ্দিন | BD10090021 |  |
| Barishal | Upazila, Jhalokati | Nalchhity | নলছিটি | BD10420073 |  |
| Barishal | Upazila, Pirojpur | Indurkani | ইন্দুরকানী | BD10790090 | জিয়ানগর |
| Barishal | Upazila, Pirojpur | Nesarabad (Swarupkathi) | নেছারাবাদ (স্বরূপকাঠি) | BD10790087 | নেছারাবাদ (স্বরূপকাঠী) |
| Chattogram | Upazila, Bandarban | Naikkhongchhari | নাইক্ষ্যংছড়ি | BD20030073 |  |
| Chattogram | City corporation, Chattogram | Chattogram City Corporation | চট্টগ্রাম সিটি কর্পোরেশন | BD20151600 |  |
| Chattogram | Upazila, Chattogram | Karnaphuli | কর্ণফুলী | BD20150039 |  |
| Chattogram | Upazila, Cumilla | Adarsha Sadar | আদর্শ সদর | BD20190067 |  |
| Chattogram | City corporation, Cumilla | Cumilla City Corporation | কুমিল্লা সিটি কর্পোরেশন | BD20195000 |  |
| Chattogram | Upazila, Cumilla | Lalmai | লালমাই | BD20190073 |  |
| Chattogram | Upazila, Cumilla | Manoharganj | মনোহরগঞ্জ | BD20190074 |  |
| Chattogram | Upazila, Cumilla | Sadar Dakkhin | সদর দক্ষিণ | BD20190033 |  |
| Chattogram | Upazila, Feni | Chhagalnaiya | ছাগলনাইয়া | BD20300014 |  |
| Chattogram | Upazila, Feni | Daganbhuiyan | দাগনভূঁঞা | BD20300025 | দাগনভূঞা |
| Chattogram | Upazila, Feni | Fulgazi | ফুলগাজী | BD20300041 |  |
| Chattogram | Upazila, Khagrachhari | Guimara | গুইমারা | BD20460047 |  |
| Chattogram | Upazila, Lakshmipur | Kamalnagar | কমলনগর | BD20510033 |  |
| Chattogram | Upazila, Noakhali | Senbag | সেনবাগ | BD20750080 |  |
| Chattogram | Upazila, Noakhali | Subarnachar | সুবর্ণচর | BD20750085 |  |
| Chattogram | Upazila, Rangamati | Jurachhari | জুরাছড়ি | BD20840047 |  |
| Chattogram | Upazila, Rangamati | Naniarchar | নানিয়ারচর | BD20840075 |  |
| Dhaka | City corporation, Dhaka | Dhaka North City Corporation | ঢাকা উত্তর সিটি কর্পোরেশন | BD30262500 |  |
| Dhaka | City corporation, Dhaka | Dhaka South City Corporation | ঢাকা দক্ষিণ সিটি কর্পোরেশন | BD30262000 |  |
| Dhaka | Upazila, Faridpur | Saltha | সালথা | BD30290090 |  |
| Dhaka | City corporation, Gazipur | Gazipur City Corporation | গাজীপুর সিটি কর্পোরেশন | BD30333300 |  |
| Dhaka | Upazila, Gazipur | Sreepur | শ্রীপুর | BD30330086 |  |
| Dhaka | Upazila, Munshiganj | Louhajang | লৌহজং | BD30590044 |  |
| Dhaka | City corporation, Narayanganj | Narayanganj City Corporation | নারায়ণগঞ্জ সিটি কর্পোরেশন | BD30674400 |  |
| Dhaka | Upazila, Narsingdi | Raipura | রায়পুরা | BD30680064 |  |
| Dhaka | Upazila, Rajbari | Goalanda | গোয়ালন্দ | BD30820029 |  |
| Dhaka | Upazila, Shariatpur | Zajira | জাজিরা | BD30860094 |  |
| Dhaka | Upazila, Tangail | Bhuanpur | ভূঞাপুর | BD30930019 |  |
| Khulna | Upazila, Bagerhat | Morelganj | মোড়েলগঞ্জ | BD40010060 | মোরেলগঞ্জ |
| Khulna | Upazila, Bagerhat | Sharankhola | শরণখোলা | BD40010077 |  |
| Khulna | Upazila, Jashore | Bagharpara | বাঘারপাড়া | BD40410009 |  |
| Khulna | Upazila, Jashore | Jashore Sadar | যশোর সদর | BD40410047 |  |
| Khulna | Upazila, Jhenaidah | Harinakundu | হরিণাকুণ্ডু | BD40440014 | হরিণাকুন্ডু |
| Khulna | City corporation, Khulna | Khulna City Corporation | খুলনা সিটি কর্পোরেশন | BD40473300 |  |
| Khulna | Upazila, Satkhira | Ashashuni | আশাশুনি | BD40870004 |  |
| Mymensingh | Upazila, Jamalpur | Bakshiganj | বকশীগঞ্জ | BD45390007 |  |
| Mymensingh | Upazila, Mymensingh | Fulpur | ফুলপুর | BD45610081 |  |
| Mymensingh | Upazila, Mymensingh | Gouripur | গৌরীপুর | BD45610023 |  |
| Mymensingh | City corporation, Mymensingh | Mymensingh City Corporation | ময়মনসিংহ সিটি কর্পোরেশন | BD45614000 |  |
| Mymensingh | Upazila, Mymensingh | Tarakanda | তারাকান্দা | BD45610088 |  |
| Rajshahi | Upazila, Bogura | Dupchachia | দুপচাঁচিয়া | BD50100033 |  |
| Rajshahi | District | Chapainababganj | চাঁপাইনবাবগঞ্জ | BD5070 |  |
| Rajshahi | Upazila, Chapainababganj | Chapainawabganj Sadar | চাঁপাইনবাবগঞ্জ সদর | BD50700066 |  |
| Rajshahi | Upazila, Naogaon | Mahadebpur | মহাদেবপুর | BD50640050 |  |
| Rajshahi | Upazila, Natore | Gurudaspur | গুরুদাসপুর | BD50690041 |  |
| Rajshahi | Upazila, Natore | Naldanga | নলডাঙ্গা | BD50690055 |  |
| Rajshahi | Upazila, Natore | Singra | সিংড়া | BD50690091 |  |
| Rajshahi | City corporation, Rajshahi | Rajshahi City Corporation | রাজশাহী সিটি কর্পোরেশন | BD50816600 |  |
| Rangpur | Upazila, Dinajpur | Birol | বিরল | BD55270017 |  |
| Rangpur | Upazila, Dinajpur | Fulbari | ফুলবাড়ী | BD55270038 |  |
| Rangpur | Upazila, Kurigram | Rajibpur | রাজিবপুর | BD55490008 | চর রাজিবপুর |
| Rangpur | Upazila, Kurigram | Roumari | রৌমারী | BD55490079 |  |
| Rangpur | Upazila, Panchagarh | Atowari | আটোয়ারী | BD55770004 |  |
| Rangpur | City corporation, Rangpur | Rangpur City Corporation | রংপুর সিটি কর্পোরেশন | BD55857500 |  |
| Sylhet | Upazila, Sunamganj | Bishwambharpur | বিশ্বম্ভরপুর | BD60900018 |  |
| Sylhet | Upazila, Sunamganj | Dharmapasha | ধর্মপাশা | BD60900032 |  |
| Sylhet | Upazila, Sunamganj | Shalla | শাল্লা | BD60900086 |  |
| Sylhet | Upazila, Sylhet | Osmaninagar | ওসমানীনগর | BD60910060 |  |
| Sylhet | City corporation, Sylhet | Sylhet City Corporation | সিলেট সিটি কর্পোরেশন | BD60915000 |  |
